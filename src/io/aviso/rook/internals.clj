(ns io.aviso.rook.internals
  "Unsupported internal functions used in the implementation."
  (:require
    [io.aviso.rook.utils :as utils]
    [medley.core :as medley]
    [clojure.core.async :as async]
    [clojure.tools.logging :as l])
  (:import (javax.servlet.http HttpServletResponse)))

(defn prefix-with
  "Like concat, but with arguments reversed."
  [coll1 coll2]
  (concat coll2 coll1))

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

(defn to-api-keyword
  "Converts a keyword with embedded dashes into one with embedded underscores."
  [kw]
  (-> kw
      name
      (.replace \- \_)
      keyword))

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

(defn extract-argument-value
  "Uses the arg-resolvers to identify the resolved value for an argument. First a check for
  the keyword version of the argument (which is a symbol) takes place. If that resolves as nil,
  a second search occurs, using the API version of the keyword (with dashes converted to underscores)."
  [argument request arg-resolvers]
  (let [arg-kw (keyword (if (map? argument)
                          (-> argument
                              :as
                              (or (throw (IllegalArgumentException. "map argument has no :as key")))
                              name)
                          (name argument)))]
    (or
      (some #(% arg-kw request) arg-resolvers)
      (let [api-kw (to-api-keyword arg-kw)]
        (if-not (= arg-kw api-kw)
          (some #(% api-kw request) arg-resolvers))))))

(defn- is-var-a-function?
  "Checks if a var resolved from a namespace is actually a function."
  [v]
  (-> v
      deref
      ifn?))

(defn- ns-function
  "Return the var for the given namespace and function keyword."
  [namespace function-key]
  (when-let [v (ns-resolve namespace (symbol (name function-key)))]
    (when (is-var-a-function? v) ;it has to be a function all right
      v)))

(defn to-message [^Throwable t]
  (or (.getMessage t)
      (-> t .getClass .getName)))

(defn wrap-with-arg-resolvers [handler arg-resolvers]
  (fn [request]
    (handler (update-in request [:rook :arg-resolvers] prefix-with arg-resolvers))))

(defmacro safety-first
  "Provides a safe environment for the implementation of a thread or go block; any uncaught exception
  is converted to a 500 response.

  The request is used when reporting the exception (it contains a :request-id
  key set by `io.aviso.client/send`)."
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
  fabricate a response. Requires at least one expression."
  [request expr & more]
  `(async/go (safety-first ~request ~expr ~@more)))

(defmacro safe-thread
  "Wraps the body in a [[safety-first]] block and then in a thread block. The request is used by [[safety-first]] if it must
  fabricate a response. Requires at least one expression."
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
