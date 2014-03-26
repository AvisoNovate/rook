(ns io.aviso.rook.async
  "Provides utilities for invoking and implementing asynchronous handlers and middleware.

  Async handlers operate much the same as normal Ring handlers: they are passed a Ring request map,
  and they compute and return a Ring response. The difference is that the handler is typically
  implemented as a core.async go block, and the return value is a channel that recieves the Ring response
  map.

  Because nil is not a valid return value from a go block, the value false (wrapped in a channel)
  is used when a request handler, or middleware, chooses not to process the request.

  Async middleware comes in two categories.  Basic middleware, that simply adds or modifies
  the Ring request map works the same, and passes the result of the delegate handler through
  unchanged (but that result will be channel, not the actual Ring response).

  Complex middleware that operates on the return value from the delegated handler is more complex.
  The delegates handler should be invoked inside a go block, so that the result from the handler
  can be obtained without blocking."
  (:use
    [clojure.core.async :only [chan go >! <! <!! >!!]])
  (:require
    [clojure.tools.logging :as l]
    [clout.core :as clout]
    [io.aviso.rook :as rook]))

(defmacro try-go
  "Wraps the body in a go block and a try block. The try block will
  catch any throwable and log it as an error, then return a status 500 response.

  The request is used when reporting the exception (it contains a :request-id
  key set by io.aviso.client/send)."
  [request & body]
  `(go
     (try
       ~@body
       (catch Throwable t#
         (let [r# ~request]
           (l/errorf t# "Exception processing request %s (%s `%s')"
                     (:request-id r#)
                     (-> r# :request-method name .toUpperCase)
                     (:uri r#)))
         {:status 500}))))

(defn async-handler->ring-handler
  "Wraps an asynchronous handler function as a standard synchronous handler."
  [async-handler]
  ;; Here's where Netty, instead of Jetty, would be nice. While Clojure is doing async work,
  ;; the Jetty request handling thread blocks.
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
  "Wraps a syncronous Ring handler function as an asynchrounous handler. The handler is invoked immediately
  (not in another thread), but the result is wrapped in a channel."
  [handler]
  (fn [request]
    (-> request handler result->channel)))

(defn routing
  "Routes a request to sequence of async handlers. Each handler should return a channel
  that contains either a Ring response map or false (nil is not an allowed value over a channel). Handlers
  are typically implemented using core.async go or thread blocks."
  [request & handlers]
  (go
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
  may themselves be synchronous or asynchronous (the default).
  The function meta-data key :sync can be set to true
  to indicate that the handler is synchronous.

  For an asynchronous handler (one implemented around a go or thread block),
  the function should return a channel that will receive the ultimate result.
  The function may return false to allow the search for a handler to continue.

  For a synchronous handler (which must have the :sync meta-data), the function
  is invoked immediately, and its result wrapped in a channel.

  If no resource handler function has been identified, this function
  will return false (wrapped in a channel)."
  [{{meta-data :metadata f :function} :rook :as request}]
  (cond
    (nil? f) (result->channel false)
    (:sync meta-data) (-> request
                          rook/rook-dispatcher
                          result->channel)
    :else (or
            (rook/rook-dispatcher request)
            (throw (ex-info
                     (format "Function %s, invoked as an asynchronous request handler, returned nil." f)
                     request)))))

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
  should be asynchronous (returning a channel, not a direct result)."
  [path namespace-name handler]
  (let [handler' (rook/namespace-middleware handler namespace-name)]
    (context path handler')))

(defn wrap-with-loopback
  "Wraps a set of asynchronous routes with a loopback: a function that calls back into the same asynchronous routes.
  This is essentially the whole point of of the asynchronous support: to allow individual resource handler functions
  to interact with other resources as if via HTTP/HTTPs, but without the cost (in terms of processing time to
  encode and decode requests, and in terms of blocking the limited number of request servicing threads.

  Request processing should be broken up into two phrases: a synchronous phase that is largely concerned with
  standard protocol issues (such as converting the body from JSON or EDN text into Clojure data) and an
  asynchronous phase (the routes provides to this function).

  The io.aviso.rook.client namespace is specifically designed to allow resources to communiate with each other
  via the loopback.

  handler - delegate asynchronous handler, typically via namespace-handler and or routes
  k - the keyword added to the Ring request to identify the loopback handler function; :loopback-handler by default.


  The loopback handler is exposed via arg-resolver-middleware: resource handler functions can gain access
  to the loopback by providing an argument with a matching name."
  ([handler]
   (wrap-with-loopback handler :loopback-handler))
  ([handler k]
   (let [loopback-handler (atom nil) ; for want of a this
         handler' (fn [request]
                    (let [this @loopback-handler
                          wrapped (rook/arg-resolver-middleware handler (rook/build-map-arg-resolver k this))
                          request' (assoc request k this)]
                      (wrapped request')))]
     (reset! loopback-handler handler')
     handler')))