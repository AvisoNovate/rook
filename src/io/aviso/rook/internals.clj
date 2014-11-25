(ns io.aviso.rook.internals
  "Unsupported internal functions used in the implementation."
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

(defn- require-port?
  [scheme port]
  (case scheme
    :http (not (= port 80))
    :https (not (= port 443))
    true))

(defn resource-uri-for
  "Backs [[io.aviso.rook/resource-uri-arg-resolver]] and
  ^:resource-uri tag support in [[io.aviso.rook.dispatcher]]."
  [request]
  (let [server-uri (or (:server-uri request)
                       (str (-> request :scheme name)
                            "://"
                            (-> request :server-name)
                            (let [port (-> request :server-port)]
                              (if (require-port? (:scheme request) port)
                                (str ":" port)))))]
    (str server-uri (:context request) "/")))

(defmacro safety-first
  "Provides a safe environment for the implementation of a thread or go block; any uncaught exception
  is converted to a 500 response.

  The request is used when reporting the exception (it contains a :request-id
  key set by [[io.aviso.rook.client/send]])."
  [request & body]
  `(try
     ~@body
     (catch Throwable t#
       (let [r# ~request]
         (l/errorf t# "Exception processing request %s (%s)"
                   (:request-id r# (or "<INCOMING>"))
                   (utils/summarize-request r#)))
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

(defn throwable->failure-response
  "Wraps a throwable into a 500 (Internal Server Error) response."
  {:since "0.1.11"}
  [t]
  (-> (utils/response HttpServletResponse/SC_INTERNAL_SERVER_ERROR
                      (to-message t))
      (r/content-type "text/plain")))