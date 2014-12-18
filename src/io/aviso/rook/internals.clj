(ns io.aviso.rook.internals
  "Unsupported internal functions used in the implementation."
  {:no-doc true}
  (:require
    [io.aviso.toolchest.exceptions :refer [to-message]]
    [io.aviso.rook.utils :as utils]
    [medley.core :as medley]
    [clojure.core.async :as async]
    [clojure.tools.logging :as l]
    [ring.util.response :as r])
  (:import (javax.servlet.http HttpServletResponse)))

(defn to-clojureized-keyword
  "Converts a keyword with embedded underscores into one with embedded dashes."
  [kw]
  (-> kw
      name
      (.replace \_ \-)
      keyword))

(defn clojurized-params-arg-resolver [request]
  (->> request
       :params
       (medley/map-keys to-clojureized-keyword)))

(defmacro safety-first
  "Provides a safe environment for the implementation of a thread or go block; any uncaught exception
  is converted to a 500 response.

  The request is used when reporting the exception; it is passed the incoming request
  so that it can report the method and URI."
  [request & body]
  `(try
     ~@body
     (catch Throwable t#
       (let [r# ~request]
         (l/error t# "Exception processing request" (utils/summarize-request r#)))
       (utils/response HttpServletResponse/SC_INTERNAL_SERVER_ERROR
                       {:exception (to-message t#)}))))

(defmacro safe-go
  "Wraps the body in a [[safety-first]] block and then in a go block. The request is used by [[safety-first]] if it must
  log an error. Requires at least one expression."
  [request expr & more]
  `(async/go (safety-first ~request ~expr ~@more)))

(defmacro safe-thread
  "Wraps the body in a [[safety-first]] block and then in a thread block. The request is used by [[safety-first]] if it must
  log an error. Requires at least one expression."
  [request expr & more]
  `(async/thread (safety-first ~request ~expr ~@more)))

(defn async-handler->ring-handler
  "Wraps an asynchronous handler function as a standard synchronous handler. The synchronous handler uses `<!!`, so it may block."
  [async-handler]
  (fn [request]
    (-> request async-handler async/<!!)))

(defn result->channel
  "Wraps the result from a synchronous handler into a channel. Non-nil results are `put!` on to the channel;
  a nil result causes the channel to be `close!`ed."
  [result]
  (let [ch (async/chan 1)]
    (if (some? result)
      (async/put! ch result)
      (async/close! ch))
    ch))

(defn ring-handler->async-handler
  "Wraps a syncronous Ring handler function as an asynchronous handler. The handler is invoked in another
   thread, and a nil response is converted to a `close!` action."
  [handler]
  (fn [request]
    (safe-thread request (handler request))))

(defn get-injection
  "Retrieves an injected value stored in the request. Throws an exception if the value is falsey."
  [request injection-key]
  {:pre [(some? request)
         (keyword? injection-key)]}
  (or
    (get-in request [:io.aviso.rook/injections injection-key])
    (throw (ex-info (format "Unable to retrieve injected value for key `%s'." injection-key)
                    {:request request}))))

(defn- convert-middleware-form
  [handler-sym metadata-sym form]
  `(or
     ~(if (list? form)
        (list* (first form) handler-sym metadata-sym (rest form))
        (list form handler-sym metadata-sym))
     ~handler-sym))

(defmacro compose-middleware
  "Assembles multiple endpoint middleware forms into a single endpoint middleware. Each middleware form
  is either a list or a single form, that will be wrapped as a list.

  The list is modified so that the first two values passed in are the previous handler and the metadata (associated
  with the endpoint function).

  The form should evaluate to a new handler, or the old handler. As a convienience, the form may
  evaluate to nil, which will keep the original handler passed in.

  Returns a function that accepts a handler and middleware and invokes each middleware form in turn, returning
  a final handler function.

  This is patterned on Clojure's -> threading macro, with some significant differences."
  [& middlewares]
  (let [handler-sym (gensym "handler")
        metadata-sym (gensym "metadata")]
    `(fn [~handler-sym ~metadata-sym]
       (let [~@(interleave (repeat handler-sym)
                           (map (partial convert-middleware-form handler-sym metadata-sym) middlewares))]
         ~handler-sym))))
