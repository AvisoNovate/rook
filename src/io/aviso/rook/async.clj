(ns io.aviso.rook.async
  "Provides utilities for invoking and implementing asynchronous handlers and middleware.

  Async handlers operate much the same as normal Ring handlers: they are passed a Ring request map,
  and they compute and return a Ring response. The difference is that the handler is typically
  implemented as a core.async go block, and the return value is a channel that recieves the Ring response
  map.

  Because nil is not a valid return value from a go block, the value false (wrapped in a channel)
  is used when a request handler, or middleware, chooses not to process the request.

  Async middleware comes in three categories.  Basic middleware, that simply adds or modifies
  the Ring request map works the same, and passes the result of the delegate handler through
  unchanged (but that result will be channel, not the actual Ring response).

  Intercepting middleware may perform an immediate (non-asynchronous) computation and return a value,
  or may proceed directly to the delegate asynchronous handler. The returned value must be wrapped
  in a channel (using result->channel). An example of this is middleware that performs authentication
  or input validation.

  Complex middleware that operates on the return value from the delegated handler, or must
  perform its own async operations, is more complex.
  The delegated handler should be invoked inside a go block, so that the result from the handler
  can be obtained without blocking."
  (:import (javax.servlet.http HttpServletResponse))
  (:require
    [clojure.core.async :refer [chan go >! <! <!! >!! thread]]
    [clojure.tools.logging :as l]
    [clout.core :as clout]
    [ring.middleware
     [session :as session]
     [format-params :as format-params]
     [format-response :as format-response]]
    [io.aviso.rook :as rook]
    [io.aviso.rook.internal.exceptions :as exceptions]
    [io.aviso.rook
     [schema-validation :as sv]
     [utils :as utils]]))

(defmacro safety-first
  "Provides a safe environment for the implementation of a thread or go block; any uncaught exception
  is converted to a 500 response. Also, if the body evaluates to nil, it is converted to false.

  The request is used when reporting the exception (it contains a :request-id
  key set by io.aviso.client/send)."
  [request & body]
  `(or
     (try
       ~@body
       (catch Throwable t#
         (let [r# ~request]
           (l/errorf t# "Exception processing request %s (%s)"
                     (:request-id r# (or "<INCOMING>"))
                     (utils/summarize-request r#)))
         (utils/response HttpServletResponse/SC_INTERNAL_SERVER_ERROR
                         {:exception (exceptions/to-message t#)})))
     false))


(defmacro safe-go
  "Wraps the body in a safety-first blocks and a go block. The request is used by safety-first if it must
  fabricate a response."
  [request & body]
  `(go (safety-first ~request ~@body)))

(defmacro safe-thread
  "Wraps the body in a safety-first blocks and a thread block. The request is used by safety-first if it must
  fabricate a response."
  [request & body]
  `(thread (safety-first ~request ~@body)))

(defn async-handler->ring-handler
  "Wraps an asynchronous handler function as a standard synchronous handler."
  [async-handler]
  (fn [request]
    (-> request async-handler <!!)))

(defn result->channel
  "Wraps the result from a synchronous handler into a channel. The result is put into the channel, though
  nil is converted to false."
  [result]
  (let [ch (chan 1)]
    (>!! ch (or result false))
    ch))

(defn ring-handler->async-handler
  "Wraps a syncronous Ring handler function as an asynchrounous handler. The handler is invoked in another
   thread, and a nil response is converted to false."
  [handler]
  (fn [request]
    (safe-thread request (handler request))))

(defn routing
  "Routes a request to sequence of async handlers. Each handler should return a channel
  that contains either a Ring response map or false (nil is not an allowed value over a channel). Handlers
  are typically implemented using core.async go or thread blocks."
  [request & handlers]
  (safe-go request
    (loop [[handler & more] handlers]
      (if handler
        ;; Invoke the handler asynchronously and park until it responds.
        (let [result-ch (handler request)
              result (<! result-ch)]
          ;; Again, a result of false means "continue the search".
          (if-not (false? result)
            result
            (recur more)))
        ;; And since there was no match yet, we signal to continue the search.
        false))))

(defn routes
  "Creates an async handler that routes to a number of other async handlers."
  [& handlers]
  #(apply routing % handlers))

(defn async-rook-dispatcher
  "Replaces the default (synchronous) rook dispatcher. Resource handler methods
  may themselves be synchronous or asynchronous, with asynchronous the default.

  The function meta-data key :sync can be set to true
  to indicate that the handler is synchronous.

  For an asynchronous handler (one implemented around a go or thread block),
  the function should return a channel that will receive the ultimate result.
  The function may return false to allow the search for a handler to continue.

  Asynchronous handler functions should be careful to return a 500 response if there is
  a failure (e.g., a thrown exception).  For synchronous functions, a try block is provided
  to generate the 500 response if an exception is thrown.

  For a synchronous handler (which must have the :sync meta-data), the function
  is invoked in a thread, and its result wrapped in a channel (with nil converted to false).

  If no resource handler function has been identified, this function
  will return false (wrapped in a channel)."
  [{{meta-data :metadata f :function} :rook :as request}]
  (cond
    (nil? f) (result->channel false)
    (:sync meta-data) (safe-thread request
                                   (rook/rook-dispatcher request))
    :else (or
            (rook/rook-dispatcher request)
            (throw (ex-info
                     (format "Function %s, invoked as an asynchronous request handler, returned nil." f)
                     request)))))

(defn wrap-with-schema-validation
  "The asynchronous version of schema validation, triggered by the :schema meta-data attribute."
  [handler]
  (fn [request]
    (or
      (when-let [schema (-> request :rook :metadata :schema)]
        (when-let [failure-response (sv/validate-against-schema request schema)]
          (result->channel failure-response)))
      (handler request))))

(def default-rook-pipeline
  "The default rook pipeline for async processing. Wraps async-rook-dispatcher with middleware to
  set the :arg-resolvers specific to the function, and to peform schema validation."
  (-> async-rook-dispatcher
      rook/wrap-with-function-arg-resolvers
      wrap-with-schema-validation))

;;; Have to much about with some private functions in compojure.core ... at least, until we
;;; (perhaps) move Rook directly to clout.

(defn- compojure-alias [symbol] (-> 'compojure.core ns-map (get symbol) deref))

(def wrap-context-alias (compojure-alias 'wrap-context))
(def context-route-alias (compojure-alias 'context-route))
(def assoc-route-params-alias (compojure-alias 'assoc-route-params))

(defn if-route
  "Async version of Compojure's if-route."
  [route handler]
  (fn [request]
    (if-let [params (clout/route-matches route request)]
      (handler (assoc-route-params-alias request params))
      (result->channel false))))

(defmacro context
  "Give all routes in the form a common path prefix. A simplified version of Compojure's context."
  [path & routes]
  `(if-route ~(context-route-alias path)
             (wrap-context-alias
               (fn [request#]
                 (routing request# ~@routes)))))

(defn namespace-handler
  "Asynchronous namespace handler. Adds namespace middleware, but the handler (including any middleware on the handler)
  should be asynchronous (returning a channel, not a direct result).

  In most cases, a path will be specified and the context macro used to put the new handler inside the context. However,
  path may also be nil."
  ([namespace-name]
   (namespace-handler nil namespace-name))
  ([path namespace-name]
   (namespace-handler path namespace-name default-rook-pipeline))
  ([path namespace-name handler]
   (let [handler' (rook/namespace-middleware handler namespace-name)]
     (if path
       (context path handler')
       handler'))))

(defn wrap-with-loopback
  "Wraps a set of asynchronous routes with a loopback: a function that calls back into the same asynchronous routes.
  This is essentially the whole point of of the asynchronous support: to allow individual resource handler functions
  to interact with other resources as if via HTTP/HTTPs, but without the cost, in terms of processing time to
  encode and decode requests, and in terms of blocking the limited number of request servicing threads.

  Request processing should be broken up into two phases: an initial synchronous phase that is largely concerned with
  standard protocol issues (such as converting the body from JSON or EDN text into Clojure data) and a later,
  asynchronous phase (the routes provided to this function as handler).

  The loopback allows this later asynchronous phase to be re-entrant.

  The io.aviso.rook.client namespace is specifically designed to allow resources to communiate with each other
  via the loopback.

  handler - delegate asynchronous handler, typically via namespace-handler and/or routes
  k - the keyword added to the Ring request to identify the loopback handler function; :loopback-handler by default.

  The loopback handler is exposed via arg-resolver-middleware: resource handler functions can gain access
  to the loopback by providing an argument with a matching name. The default Ring request key is :loopback-handler."
  ([handler]
   (wrap-with-loopback handler :loopback-handler))
  ([handler k]
   (letfn [(handler' [request]
                     (let [wrapped (rook/arg-resolver-middleware handler (rook/build-map-arg-resolver k handler'))
                           request' (assoc request k handler')]
                       (wrapped request')))]
     handler')))


(defn wrap-restful-format
  "Asychronous version of ring.middleware.format/wrap-restful-format; this implementation uses
  go blocks and tricks to work inside an asynchronous pipeline."
  ([handler]
   (wrap-restful-format handler [:json-kw :edn]))
  ([handler formats]
   (let [req-handler (format-params/wrap-restful-params handler :formats formats)
         req-handler' (fn [request]
                        (safe-go request
                                 (-> request req-handler <!)))]
     (fn [request]
       (safe-go request
                (if-let [response (-> request req-handler' <!)]
                  (->
                    ;; "Fake" handler. This is an ugly bit of hack to allow the execution
                    ;; of the true handlers earlier in the go block, and have the response
                    ;; (if not nil), already taken from the channel.
                    (constantly response)
                    (format-response/wrap-restful-response :formats formats)
                    ;; And now we can invoke our "just brewed up" handler (wrapped around our
                    ;; standin for the already executed handler).
                    (as-> f (f request)))
                  false))))))

(defn wrap-with-standard-middleware
  "Default asynchronous middleware."
  [handler]
  (-> handler
      rook/wrap-with-default-arg-resolvers
      wrap-restful-format
      ring.middleware.keyword-params/wrap-keyword-params
      ring.middleware.params/wrap-params))

(defn wrap-session
  "The async version of ring.middleware.session/wrap-with-session.
  Session handling is not part of the standard middleware, and must be
  added in explicitly."
  ([handler] (wrap-session handler {}))
  ([handler options]
   (let [options' (session/session-options options)]
     (fn [request]
       (safe-go request
         (let [request' (session/session-request request options')]
           (->
             (handler request')
             <!
             (session/session-response request' options'))))))))