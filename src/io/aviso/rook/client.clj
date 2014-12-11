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
    [clojure.string :as str]
    [io.aviso.toolchest.macros :refer [cond-let]]
    [io.aviso.toolchest.collections :refer [pretty-print pretty-print-brief]]))

(defn new-request
  "Creates a new Ring request that will ultimately be passed to the handler function.

  The API is fluid, with calls to various functions taking and returning the Ring request
  as the first parameter; these can be assembled using the -> macro.

  The handler function is passed the Ring request map and returns a
  core.async channel that will receive the Ring response map.  The Ring map
  will have keys :request-method, :uri (see [[to]]), and keys
  :headers, :query-params and :body-params.

  The handler function should use this information generate an HTTP/HTTPs request and capture
  the response as a Ring response map. Some handlers may require additional keys, for example,
  \"accept\" or \"content-type\" headers.

  The handler is typically implemented using the clojure.core.async go or thread macros.

  A minimal sample handler implementation is provided by [[io.aviso.rook.clj-http/handler]]."
  [handler]
  {:pre [(some? handler)]}
  {:handler handler})

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
  (assoc request :request-method method
         :uri
         (->>
           paths
           (map element-to-string)
           (str/join "/"))))

(defn to
  "Targets the request with a method (:get, :post, etc.) and a path; the path is a series of
  elements, each either a keyword or a string, or any other type (that is converted to a string).

  The :uri key of the Ring request is set from this, it consists of the path elements seperated by slashes.

  Keywords are converted to strings using the name function (so the leading colon is not part of the
  path element).  All other types are converted using str.

  Example:

       (-> (c/new-request clj-http-handler)
           (c/to :post :hotels hotel-id :rooms room-number)
           c/send
           (c/then ...))

  This will build a :uri resembling \"hotels/1234/rooms/237\"."
  [request method & path]
  (to* request method path))

(defn with-body-params
  "Merges a Clojure map into the body of the request (as if EDN content was parsed into Clojure data), merging
  it with any previously set body parameters.

  By convention, the keys are keywords and the values are as appropriate for the content type (strings for
  form encoded information, or more complex data types when using JSON or EDN)."
  [request params]
  {:pre [(map? params)]}
  (update-in request [:body-params] merge params))

(defn with-query-params
  "Adds parameters to the :query-params key using merge. The query parameters should use keywords for keys. The
  :params key of the Ring request will be the merge of :query-params and :body-params.  By convention, the
  keys are keywords and the values are strings (or seqs of strings)."
  [request params]
  {:pre [(map? params)]}
  ;; TODO convert to update when Clojure 1.7
  (update-in request [:query-params] merge params))

(defn with-headers
  "Merges the provided headers into the :headers key of the Ring request. Keys should be lower-cased strings.

  The keys should follow the Ring convention of lower-case strings, the values should be strings."
  [request headers]
  (update-in request [:headers] merge headers))

(defn is-success?
  [status]
  (<= 200 status 299))

(defn send
  "Sends the request asynchronously, using the web-service-handler provided to new-request.
  Returns a channel that will receive the Ring result map.

  The [[then]] macro is useful for working with this result channel."
  [request]
  (assert (and (:request-method request)
               (:uri request))
          "No target (request method and URI) has been specified.")
  ((:handler request) request))

(defn- ->cond-block
  [form response-sym response-clause]
  (cond-let
    (= response-clause :pass)
    response-sym

    (not (list? response-clause))
    (throw (ex-info (format "The block for a response clause must be a list containing a vector and a series of forms (in %s: %d)."
                            *ns* (-> form meta :line))
                    {:response-clause response-clause}))

    [[params & forms] response-clause]

    (not (and (vector? params) (= 1 (count params))))
    (throw (ex-info (format "The first form in the response clause must be a single-element vector to be bound to the response (in %s: %d)."
                            *ns* (-> form meta :line))
                    {:response-clause response-clause}))

    ;; zero forms is allowed, will evaluate to nil.  Probably look like (c/then :success ([_])).

    :else
    `(let [~(first params) ~response-sym] ~@forms)))

(defn- build-cond-clauses
  [form response-sym status-sym clauses]
  (let [->cond-block' (partial ->cond-block form response-sym)]
    (loop [cond-clauses []
           [selector clause-block & remaining-clauses] clauses]
      (case selector
        nil cond-clauses

        :else
        (recur (conj cond-clauses true (->cond-block' clause-block))
               remaining-clauses)

        :success
        (recur (conj cond-clauses
                     `(is-success? ~status-sym)
                     (->cond-block' clause-block))
               remaining-clauses)

        :failure
        (recur (conj cond-clauses
                     `(not (is-success? ~status-sym))
                     (->cond-block' clause-block))
               remaining-clauses)

        :pass-success
        (recur (conj cond-clauses
                     `(is-success? ~status-sym)
                     response-sym)
               ;; There is no clause block after :pass-success or :pass-failure
               (cons clause-block remaining-clauses))

        :pass-failure
        (recur (conj cond-clauses
                     `(not (is-success? ~status-sym))
                     response-sym)
               ;; There is no clause block after :pass-success or :pass-failure
               (cons clause-block remaining-clauses))

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
  makes it easier to work with that channel, branching based on response status code, and returning a new
  result from the channel.

  then makes use of <! (to park until the response form the channel is available),
  and can therefore only be used inside a go block. Use [[then*]] outside of a go block.

  channel
  : the expression which produces the channel, e.g., the result of invoking [[send]].

  clauses
  : indicate what status code(s) to respond to, and what to do with the response

  A status clause can either be :pass-success, :pass-failure, or a single status code followed by
  a response clause.

  A response clause indicates what to do with the response.

  A response clause can be the keyword :pass, in which case the response is passed through
  unchanged (by passed through, we mean, becomes the evaluated value of the entire then block).

  Alternately, the response clause may be a list consisting of a one element vector followed by
  a number of forms.  The lone symbol in the vector is bound to the response, and the other forms
  are evaluated. The final form becomes the evaluation of the entire then block.

  The symbol may be a map as well, which will be used for destructuring.

  then is specifically designed to work within a go block; this means that within a clause,
  it is allowed to use the non-blocking forms <!, >!, and so forth (this would not be possible
  if the then macro worked by relating a status code to a callback function).

  Instead of a specific status code, you may use :success (any 2xx status code), :failure (any other
  status code), or :else (which matches regardless of status code).

  Status checks occur in top down order.

  Often it is desirable to simply pass the response through unchanged; this is the purpose of
  the :pass-success and :pass-failure clauses.

  :pass-success is equvalent to :success :pass and :pass-failure is equivalent to :failure :pass.

  Example:

      (-> (c/new-request handler)
          (c/to :get :todos todo-id)
          c/send
          (c/then

            HttpServletResponse/SC_NOT_MODIFIED :pass

            :success ([{body :body :as response}]
                       (update-local-cache todo-id body)
                       response)

            :pass-failure))


  It is necessary to provide a handler for all success and failure cases; an unmatched status code
  at runtime will cause an exception to be thrown."
  [channel & clauses]
  `(then* (<! ~channel) ~@clauses))