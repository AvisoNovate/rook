(ns io.aviso.rook.async
  "Provides utilities for invoking and implementing asynchronous handlers and middleware.

  Async handlers operate much the same as normal Ring handlers: they are passed a Ring request map,
  and they compute and return a Ring response. The difference is that the handler is typically
  implemented as a `clojure.core.async/go` block, and the return value is a channel that recieves the Ring response
  map.

  Because nil cannot be put on a channel, handlers that would like to
  return nil should `close!` the associated channel.

  Async middleware comes in three categories.

  * Basic middleware, which simply adds or modifies the Ring request map,
    works the same as traditional synchronous middleware: it may modify the request map
    before passing it to its delegate handler; the return value will be a channel, rather than a map.

  * Intercepting middleware may perform an immediate (non-asynchronous) computation and return a value,
    or may proceed directly to the delegate asynchronous handler. The returned value must be wrapped
    in a channel (using [[result->channel]]). An example of this is middleware that performs authentication
    or input validation.

  * Complex middleware that operates on the return value from the delegated handler, or must
    perform its own async operations, is more challenging.
    The delegated handler should be invoked inside a [[safe-go]] block, so that the result from the handler
    can be obtained without blocking."
    (:import (javax.servlet.http HttpServletResponse))
  (:require
    [clojure.core.async :refer [chan go >! <! <!! >!! thread put! take! close!]]
    [ring.middleware
     [session :as session]
     [format-params :as format-params]
     [format-response :as format-response]]
    [io.aviso.rook :as rook]
    [io.aviso.rook
     [schema-validation :as sv]
     [utils :as utils]
     [internals :as internals]]))

(defmacro safety-first
  "Provides a safe environment for the implementation of a thread or go block; any uncaught exception
  is converted to a 500 response.

  The request is used when reporting the exception (it contains a :request-id
  key set by `io.aviso.client/send`)."
  [request & body]
  `(internals/safety-first ~request ~@body))

(defmacro safe-go
  "Wraps the body in a [[safety-first]] block and then in a go block. The request is used by [[safety-first]] if it must
  fabricate a response. Requires at least one expression."
  [request expr & more]
  `(internals/safe-go ~request ~expr ~@more))

(defmacro safe-thread
  "Wraps the body in a [[safety-first]] block and then in a thread block. The request is used by [[safety-first]] if it must
  fabricate a response. Requires at least one expression."
  [request expr & more]
  `(internals/safe-thread ~request ~expr ~@more))

(defn async-handler->ring-handler
  "Wraps an asynchronous handler function as a standard synchronous handler. The synchronous handler uses `<!!`, so it may block."
  [async-handler]
  (internals/async-handler->ring-handler async-handler))

(defn result->channel
  "Wraps the result from a synchronous handler into a channel. Non-nil results are `put!` on to the channel;
  a nil result causes the channel to be `close!`ed."
  [result]
  (internals/result->channel result))

(defn ring-handler->async-handler
  "Wraps a syncronous Ring handler function as an asynchronous handler. The handler is invoked in another
   thread, and a nil response is converted to a `close!` action."
  [handler]
  (internals/ring-handler->async-handler handler))

(defn wrap-with-schema-validation
  "The asynchronous version of schema validation, triggered by :schema metadata."
  [handler]
  (sv/wrap-with-schema-validation handler result->channel))

(def ^:private request-copy-properties
  "Properties of a Ring request that should be copied into a chained request."
  [:server-port :server-name :remote-addr :scheme :ssl-client-cert :server-uri :timeout-ch])

(defn wrap-with-loopback
  "Wraps a set of asynchronous routes with a loopback: a function that calls back into the same asynchronous routes.
  This is essentially the whole point of of the asynchronous support: to allow individual resource handler functions
  to interact with other resources as if via HTTP/HTTPs, but without the normal cost. Here, cost is evaluated in
  terms of the processing time to encode and decode requests, the time to perform HTTPs handshakes, and
  the number of request servicing threads that are blocked. Using async and loopbacks, nearly all of those
  costs disappear (though, of course, the use of core.async adds its own overheads).

  Request processing should be broken up into two phases: an initial phase (synchronous or
  asynchronous) that is largely concerned with standard protocol issues
  (such as converting the body from JSON or EDN text into Clojure data) and a later,
  asynchronous phase (the routes provided to this function as handler).  Alternately, the
  `io.aviso.rook.jetty-async-adapter` namespace can be used to create a request processing pipeline that
  is async end-to-end.

  The loopback allows this asynchronous processing to be re-entrant.

  The `io.aviso.rook.client` namespace is specifically designed to allow resources to communiate with each other
  via the loopback.

  handler
  : delegate asynchronous handler, typically via namespace-handler and/or routes

  k
  : _Default: :loopback-handler_
  : The keyword added to the Ring request to identify the loopback handler function; :loopback-handler by default

  The loopback handler is exposed via `wrap-with-arg-resolvers`: resource handler functions can gain access
  to the loopback by providing an argument with a matching name.

  The loopback handler will capture some request data when first invoked (in a non-loopback request); this information
  is merged into the request map provided to the delegate handler. This includes :scheme, :server-port, :server-name,
  and several others.

  :server-uri is also captured, so that the :resource-uri argument can be resolved.

  What's not captured: the :body, :params, :uri, :query-string, :headers, and all the various :*-params generated
  by standard middleware. Essentially, enough information is captured and restored to allow the eventual
  handler to make determinations about the originating request, but not know the specific URI, query parameters,
  or posted data.

  Specifically, the client in a loopback will need to explicitly decide which, if any, headers are to
  be passed through the loopback.

  In addition, the `[:rook :arg-resolvers]` key is captured, since often default argument resolvers are
  defined higher in the pipeline."
  ([handler]
   (wrap-with-loopback handler :loopback-handler))
  ([handler k]
   (fn [request]
     (let [injections (-> request ::rook/injections)
           captured-request-data (select-keys request request-copy-properties)]
       (letfn [(handler' [nested-request]
                         (let [wrapped (rook/wrap-with-arg-resolvers handler (rook/build-map-arg-resolver {k handler'}))
                               request' (-> nested-request
                                            (assoc k handler')
                                            (assoc ::rook/injections injections)
                                            (merge captured-request-data))]
                           (wrapped request')))]
         (handler' request))))))

(defn wrap-with-injections
  "Merges the given map into the map found at
  `:io.aviso.rook/injections` key in the request prior to passing it
  to the handler. May be specified multiple times in the middleware stack.

  Injections thus added to the request will be preserved for requests
  made via the loopback mechanism."
  [handler injections]
  (fn [request]
    (handler (update-in request [::rook/injections] merge injections))))

(defn get-injection
  "Extracts the injection named by the given keyword from the
  request."
  [request kw]
  (-> request ::rook/injections kw))

(defn wrap-restful-format
  "Asychronous version of `ring.middleware.format/wrap-restful-format`; this implementation uses
  will work properly inside an asynchronous pipeline."
  ([handler]
   (wrap-restful-format handler [:json-kw :edn]))
  ([handler formats]
   (let [req-handler (format-params/wrap-restful-params handler :formats formats)]
     (fn [request]
       (let [response-ch (chan 1)]
         ;; To keep things fully asynchronous, we first invoke the downstream handler.
         (take! (try
                  (req-handler request)
                  (catch Throwable t
                    (result->channel
                      (utils/response HttpServletResponse/SC_INTERNAL_SERVER_ERROR
                                      {:exception (internals/to-message t)}))))
                (fn [handler-response]
                  (if handler-response
                    (put! response-ch
                          (->
                            ;; We can't pass the asynchronous req-handler to w-r-r, it expects
                            ;; a sync handler. Instead, we provide a "fake" sync handler
                            ;; that returns the previously obtained handler response.
                            (constantly handler-response)
                            (format-response/wrap-restful-response :formats formats)
                            ;; Capture and invoke the wrapped, fake handler
                            (as-> f (f request))))
                    (close! response-ch))))
         response-ch)))))

(defn wrap-with-standard-middleware
  "The equivalent of `io.aviso.rook/wrap-with-standard-middleware`, but for an asynchronous pipeline."
  [handler]
  (-> handler
      wrap-restful-format
      ring.middleware.keyword-params/wrap-keyword-params
      ring.middleware.params/wrap-params))

(def ^:private session-options-alias
  (ns-resolve 'ring.middleware.session 'session-options))

(defn wrap-session
  "The async version of `ring.middleware.session/wrap-with-session`.
  Session handling is not part of the standard middleware, and must be
  added in explicitly."
  ([handler] (wrap-session handler {}))
  ([handler options]
   (let [options' (session-options-alias options)]
     (fn [request]
       (let [response-ch (chan 1)
             request' (session/session-request request options')]
         (take! (handler request')
                (fn [handler-response]
                  (if handler-response
                    (put! response-ch (session/session-response handler-response request' options'))
                    (close! response-ch))))
         response-ch)))))
