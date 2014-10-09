(ns io.aviso.rook.client
  "Because a significant part of implementing a service is communicating with other services, a consistent
  client library is useful. This client library is a simple DSL for assembling a partial Ring request,
  as well as specifying callbacks based on success, failure, or specific status codes.


  The implementation is largely oriented around sending the assembled Ring request to a function that
  handles the request asynchronously, returning a core.async channel to which the eventual response
  will be sent.

  Likewise, the API is opionated that the body of the request and eventual response be Clojure data (rather
  than JSON or EDN encoded strings)."
  (:refer-clojure :exclude [send])
  (:require
    [clojure.core.async :refer [go <! >! chan alt! take! put! close!]]
    [clojure.tools.logging :as l]
    [clojure.string :as str]
    [io.aviso.rook
     [async :as async]
     [utils :as utils]]))

(defn new-request
  "Creates a new client request that will utlimately become a Ring request map passed to the
  handler function.

  A client request is a structure that stores a (partial) Ring request,
  a handler function (that will be passed the Ring request), and additional data used to handle
  the response from the handler.

  The API is fluid, with calls to various functions taking and returning the client request
  as the first parameter; these can be assembled using the -> macro.

  The handler is passed the Ring request map and returns a core.async channel that will receive
  the Ring response map.

  The handler is typically implemented using the clojure.core.async go or thread macros."
  [web-service-handler]
  {:pre [(some? web-service-handler)]}
  {:handler web-service-handler})

(defn- element-to-string
  [element]
  (if (keyword? element)
    (name element)
    (str element)))

(defn to*
  "Same as [[to]], but the paths are provided as a seq, not varargs."
  {:added "0.1.10"}
  [request method paths]
  {:pre [(#{:put :post :get :delete :head :options :patch} method)]}
  (-> request
      (assoc-in [:ring-request :request-method] method)
      (assoc-in [:ring-request :uri]
                (->>
                  paths
                  (map element-to-string)
                  (str/join "/")
                  (str "/")))))

(defn to
  "Targets the request with a method (:get, :post, etc.) and a URI. The URI is composed from the path;
  each part is a keyword or a value that is converted to a string. The URI
  starts with a slash and each element in the path is seperated by a slash."
  [request method & path]
  (to* request method path))

(defn with-body-params
  "Stores a Clojure map as the body of the request (as if EDN content was parsed into Clojure data).
  The :params key of the Ring request will be the merge of :query-params and :body-params."
  [request params]
  (assert (map? params))
  (assoc-in request [:ring-request :body-params] params))

(defn with-query-params
  "Adds parameters to the :query-params key using merge. The query parameters should use keywords for keys. The
  :params key of the Ring request will be the merge of :query-params and :body-params."
  [request params]
  (update-in request [:ring-request :query-params] merge params))

(defn with-headers
  "Merges the provided headers into the :headers key of the Ring request. Keys should be lower-cased strings."
  [request headers]
  (update-in request [:ring-request :headers] merge headers))

(defn is-success?
  [status]
  (<= 200 status 299))

(defn- process-async-response
  [request uuid response]
  (assert response
          (format "Handler closed channel for request %s without sending a response." uuid))
  ;; The idea here is to only present enough to let the developer know that the right
  ;; flavor of response has been provided; often these responses can be huge.
  (l/debugf "%s - response from %s:%n%s"
            uuid
            (utils/summarize-request (:ring-request request))
            (utils/pretty-print-brief response))
  ;; In some cases, the response from upstream is returned exactly as is; for example, this
  ;; is the default behavior for a 401 status. However, downstream the content type will change
  ;; from Clojure data structures to either JSON or EDN, so the content type and length is not valid.
  ;; Content-type would get overwritten, but content-length would not, which causes the client to
  ;; have problems (attempting to read too much or too little data from the HTTP stream).
  (update-in response [:headers] dissoc "content-length" "content-type"))

(defn send
  "Sends the request asynchronously, using the web-service-handler provided to new-request.
  Returns a channel that will receive the Ring result map.

  The [[then]] macro is useful for working with this result channel.

  Each request is assigned a UUID string to its :request-id key; this is to faciliate easier tracing of the request
  and response in any logged output."
  [request]
  (let [uuid (utils/new-uuid)
        ring-request (:ring-request request)
        ring-request' (assoc ring-request :request-id uuid
                                          :params (merge (:query-params ring-request) (:body-params ring-request)))
        handler (:handler request)
        _ (assert (and (:request-method ring-request')
                       (:uri ring-request'))
                  "No target (request method and URI) has been specified.")
        _ (l/debugf "%s - request to %s%n%s"
                    uuid
                    (utils/summarize-request ring-request')
                    (-> ring-request (dissoc :request-method :uri) utils/pretty-print))
        response-ch (handler ring-request')
        result-ch (chan 1)]
    (take! response-ch
           #(put! result-ch (process-async-response request uuid %)))
    result-ch))

(defn- ->cond-block
  [form response-sym clause-block]
  (cond
      (= :pass-through clause-block)
      `~response-sym

      (or (vector? clause-block)
          (< 1 (count clause-block)))
      `(let [~(first clause-block) ~response-sym] ~@(rest clause-block))

      :else
      (throw (ex-info (format
                      "The block for a status clause must be a vector; the first value must be a symbol (or map, for destructuring), the remaining values will be evaluated; in %s: %d."
                      *ns*
                      (-> form meta :line))
                    {:clause-block clause-block}))))


(defn- build-cond-clauses
  [form response-sym status-sym clauses]
  (let [->cond-block' (partial ->cond-block form response-sym)]
    (loop [cond-clauses []
           [selector clause-block & remaining-clauses] clauses]
      (cond
        (nil? selector) cond-clauses

        (= selector :else)
        (recur (conj cond-clauses true (->cond-block' clause-block))
               remaining-clauses)

        (= selector :success)
        (recur (conj cond-clauses
                     `(is-success? ~status-sym)
                     (->cond-block' clause-block))
               remaining-clauses)

        (= selector :failure)
        (recur (conj cond-clauses
                     `(not (is-success? ~status-sym))
                     (->cond-block' clause-block))
               remaining-clauses)

        (= selector :pass-success)
        (recur (conj cond-clauses
                     `(is-success? ~status-sym)
                     response-sym)
               ;; There is no clause block after :pass-success or :pass-failure
               (cons clause-block remaining-clauses))

        (= selector :pass-failure)
        (recur (conj cond-clauses
                     `(not (is-success? ~status-sym))
                     response-sym)
               ;; There is no clause block after :pass-success or :pass-failure
               (cons clause-block remaining-clauses))

        :else
        (recur (conj cond-clauses
                     `(= ~status-sym ~selector)
                     (->cond-block' clause-block))
               remaining-clauses)))))

(defmacro then*
  "A macro that provide the underpinnings of the [[then]] macro; it extracts the status from the response
  and dispatches to the first matching clause."
  [response & clauses]
  (let [local-response (gensym "response")
        local-status (gensym "status")
        cond-clauses (build-cond-clauses &form local-response local-status clauses)]
    `(let [~local-response ~response
           ~local-status (:status ~local-response)]
       (cond
         ~@cond-clauses
         :else (throw (ex-info (format "Unmatched status code %d processing response." ~local-status)
                               {:response ~local-response}))))))

(defmacro then
  "The [[send]] function returns a channel from which the eventual result can be taken. This macro
  makes it easier to work with that channel, branching based on status code, and returning a new
  result from the channel.

  then makes use of <! (to park until the response form the channle is available),
  and can therefore only be used inside a go block. Use [[then*]] outside of a go block.

  channel - the expression which produces the channel, e.g., the result of invoking [[send]].

  A clause can either be :pass-success, :pass-failure, or a status code followed by either a vector or :pass-through.
  The first elmeent in the vector is a symbol to which the response will be bound before evaluating
  the other forms in the vector. The result of the then block is the value of the last form
  in the selected block. A :pass-through is equivalent to a vector of [x x].

  Instead of a status code, you may use :success (any 2xx status code), :failure (any other
  status code), or :else (which matches regardless of status code).

  :pass-success is equvalent to :success [x x] and :pass-failure is equivalent to :failure [x x].

  Example:

      (-> (c/new-request handler)
          (c/to :get :endpoint)
          c/send
          (c/then :success [response
                    (write-success-to-log (:body response))
                    response]

                  :pass-failure))


  It is necessary to provide a handler for all success and failure cases; an unmatched status code
  at runtime will cause an exception to be thrown."
  [channel & clauses]
  `(then* (<! ~channel) ~@clauses))