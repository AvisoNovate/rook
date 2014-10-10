(ns io.aviso.rook.internals
  "Unsupported internal functions used in the implementation."
  (:require
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

(defn- is-var-a-function?
  "Checks if a var resolved from a namespace is actually a function."
  [v]
  (-> v
      deref
      ifn?))

(defn to-message [^Throwable t]
  (or (.getMessage t)
      (-> t .getClass .getName)))

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

(defmacro cond-let
  "A merging of cond and let.  Each term is either a vector
  (in which case, it acts like let) or a condition expression followed by the value
  for that expression. An empty cond-let returns nil."
  [& forms]
  (when forms
    (if (vector? (first forms))
      `(let ~(first forms)
         (cond-let ~@(rest forms)))
      (if-not (next forms)
        (throw (IllegalArgumentException. "cond-let requires a result form to follow each test form"))
        `(if ~(first forms)
           ~(second forms)
           (cond-let ~@(drop 2 forms)))))))

(defmacro consume
  "Consume is used to break apart a collection into individual terms.  The format is

      (consume coll [symbol pred arity ...] body)

  The symbol is assigned a value by extracting zero or more values from the collection that
  match the predicate. The arity is a keyword that identifies how many values are taken
  from the collection.

  :one
  : The first value in the collection must match the predicate, or an exception is thrown.
    The value is assigned to the symbol.  The literal value 1 may be used instead of :one.

  :?
  : Matches 0 or 1 values from the collection. If the first value does not match the predicate,
    then nil is assigned to the symbol.

  :*
  : Zero or more values from the collection are assigned to the symbol. The symbol may be assigned
    an empty collection.

  :+
  : Matches one or more values; an exception is thrown if there are no matches.

  The symbol/pred/arity triplet can be followed by additional triplets.

  Although the above description discusses triplets, there are two special predicate values
  that are used as just a pair (symbol followed by special predicate), with no arity.

  :&
  : Used to indicate consumption of all remaining values, if any, from the collection.
    It is not followed by an arity, and must be the final term in the bindings vector.

  :+
  : Used to consume a single value always; this is equivalent to the sequence
    `symbol (constantly true) 1`.

  consume expands into a let form, so the symbol in each triplet may be a destructuring form."
  [coll bindings & body]
  (let [[symbol pred arity] bindings]
    (cond-let
      [binding-count (count bindings)]

      (zero? binding-count)
      `(do ~@body)

      (= pred :&)
      (if-not (= 2 binding-count)
        (throw (ex-info "Expected just symbol and :& placeholder as last consume binding."
                        {:symbol   symbol
                         :pred     pred
                         :bindings bindings}))
        `(let [~symbol ~coll] ~@body))

      (= pred :+)
      `(let [coll# ~coll]
         (if (empty? coll#)
           (throw (ex-info "consume :+ predicate on empty collection"
                           {:symbol (quote ~symbol)}))
           (let [~symbol (first coll#)]
             (consume (rest coll#) ~(drop 2 bindings) ~@body))))

      (< binding-count 3)
      (throw (ex-info "Incorrect number of binding terms for consume."
                      {:bindings bindings}))

      [remaining-bindings (drop 3 bindings)]

      :else
      (case arity

        (1 :one)
        `(let [coll# ~coll
               first# (first coll#)]
           (if-not (~pred first#)
             (throw (ex-info "consume :one arity did not match"
                             {:symbol (quote ~symbol)
                              :pred   (quote ~pred)}))
             (let [~symbol first#]
               (consume (rest coll#) ~remaining-bindings ~@body))))

        :?
        `(let [coll# ~coll
               first# (first coll#)
               match# (~pred first#)]
           (let [~symbol (if match# first# nil)]
             (consume (if match# (rest coll#) coll#) ~remaining-bindings ~@body)))

        :*
        `(let [[~symbol remaining#] (split-with ~pred ~coll)]
           (consume remaining# ~remaining-bindings ~@body))

        :+
        `(let [[matching# remaining#] (split-with ~pred ~coll)]
           (if (empty? matching#)
             (throw (ex-info "Expected to consume at least one match with :+ arity"
                             {:symbol (quote ~symbol)
                              :pred   (quote ~pred)})))
           (let [~symbol matching#]
             (consume remaining# ~remaining-bindings ~@body)))

        (throw (ex-info "Unknown arity in consume binding. Expected :one, :?, :*, or :+."
                        {:bindings bindings
                         :body     body}))))))