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
    [clojure.core.async :refer [go <! chan alt! take! put! close!]]
    [clojure.tools.logging :as l]
    [clojure.string :as str]
    [io.aviso.rook
     [async :as async]
     [utils :as utils]]))

(defn pass-headers
  "Useful in cases where the success handler is interested in the response headers, rather
  than the body of the response. For example, many responses return no body, but interesting headers (such
  as a 201 (Created).  In that situation, the `:success` callback can be overridden to this function,
  and the success clause of the [[then]] macro will receive the useful headers, rather than the empty body."
  [response]
  [nil (:headers response)])

(defn new-request
  "Creates a new request that will utlimately become a Ring request passed to the
  handler function.

  A client request is a structure that stores a (partial) Ring request,
  a handler function (that will be passed the Ring request), and additional data used to handle
  the response from the handler.

  The API is fluid, with calls to various functions taking and returning the client request
  as the first parameter; these can be assembled using the `->` macro.

  The handler is passed the Ring request map and returns a core.async channel that will receive
  the Ring response map.
  The handler is typically implemented using the `clojure.core.async` `go` or `thread` macros."
  [web-service-handler]
  {:pre [(some? web-service-handler)]}
  {:handler   web-service-handler
   ;; If there isn't an exact match on status code, the special keys :success and :failure
   ;; are checked. :success matches any 2xx code, :failure matches anything else.
   ;; :success by default returns just the body of the response in the second slot.
   ;; :failure by default returns the entire response, in the first slot.
   ;; In both cases, a couple of headers are scrubbed before passing the response
   ;; through the callback.
   :callbacks {:success (fn [response] [nil (:body response)])
               :failure vector}})

(defn- element-to-string
  [element]
  (if (keyword? element)
    (name element)
    (str element)))

(defn to
  "Targets the request with a method (`:get`, `:post`, etc.) and a URI. The URI is composed from the path;
  each part is a keyword or a value that is converted to a string. The URI
  starts with a slash and each element in the path is seperated by a slash."
  [request method & path]
  (assert (#{:put :post :get :delete :head :options} method) "Unknown method.")
  (-> request
      (assoc-in [:ring-request :request-method] method)
      (assoc-in [:ring-request :uri]
                (->>
                  path
                  (map element-to-string)
                  (str/join "/")
                  (str "/")))))

(defn with-body-params
  "Stores a Clojure map as the body of the request (as if EDN content was parsed into Clojure data.
  The `:params` key of the Ring request will be the merge of `:query-params` and `:body-params`."
  [request params]
  (assert (map? params))
  (assoc-in request [:ring-request :body-params] params))

(defn with-query-params
  "Adds parameters to the :query-params key using merge. The query parameters should use keywords for keys. The
  `:params` key of the Ring request will be the merge of `:query-params` and `:body-params`."
  [request params]
  (update-in request [:ring-request :query-params] merge params))

(defn with-headers
  "Merges the provided headers into the `:headers` key of the Ring request. Keys should be lower-cased strings."
  [request headers]
  (update-in request [:ring-request :headers] merge headers))

(defn with-callback
  "Adds or replaces a callback for the given status code. A callback of nil removes the callback.
  Instead of a specific numeric code, the keywords `:success` (for any 2xx status) or `:failure` (for
  any non-2xx status) can be provided ... but exact status code matches take precedence.  The callbacks
  are used by [[send]]."
  [request status-code callback]
  (if callback
    (assoc-in request [:callbacks status-code] callback)
    (update-in request [:callbacks] dissoc status-code)))

(defn is-success?
  [status]
  (<= 200 status 299))

(defn- identify-callback
  [callbacks status]
  (cond
    (contains? callbacks status) (get callbacks status)
    (and
      (contains? callbacks :success)
      (is-success? status)) (get callbacks :success)

    ;; Not a success code, return the :failure key (or nil).
    (not (is-success? status)) (get callbacks :failure)))

(defn- process-async-response
  [request uuid {:keys [status] :as response}]
  (assert response
          (format "Handler closed channel for request %s without sending a response." uuid))
  (let [callback (-> request :callbacks (identify-callback status))]
    (if-not callback
      (throw (ex-info (format "No callback for status %d response." status)
                      {:request-id uuid
                       :request    request
                       :response   response})))
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
    (->
      response
      (update-in [:headers] dissoc "content-length" "content-type")
      callback)))

(defn send
  "Sends the request asynchronously, using the web-service-handler provided to new-request.
  Returns a channel that will receive the Ring result map.
  When a response arrives from the handler, the correct callback is invoked.
  The value put into the result channel is the value of the response passed through the callback.

  The default callbacks produce a vector where the first value is the failure response (the entire Ring response map)
  and the second value is the body of the success response (in which case, the first value is nil).

  The intent is to use vector destructuring to determine which case occured, and proceed accordingly.

  The [[then]] macro is useful for working with this result directly.

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
        send-result-ch (chan 1)]
    (take! response-ch
           #(if-let [r (process-async-response request uuid %)]
              (put! send-result-ch r)
              (close! send-result-ch)))
    send-result-ch))

(defn- make-body [symbol body]
  (cond
    (empty? body) symbol
    (= 1 (count body)) (first body)
    :else (cons 'do body)))

(defmacro then*
  "Alternate version of the [[then]] macro, used when the vector returned by [[send]] is available."
  ([result-vector success-clause]
   `(then* ~result-vector (failure#) ~success-clause))
  ([result-vector [failure & failure-body] [success & success-body]]
   (assert failure "No failure symbol was provided to the then* macro")
   (assert success "No success symbol was provided to the then* macro")
   `(let [[~failure ~success] ~result-vector]
      (if ~failure
        ~(make-body failure failure-body)
        ~(make-body success success-body)))))

(defmacro then
  "The [[send]] function returns a channel from which the eventual result can be taken; this macro makes it easy to respond
  to either the normal success or failure cases, as determined by the __default__ callbacks. `then` makes use of `<!` and can therefore
  only be used inside a `go` block.

  channel - the expression which produces the channel, e.g., the result of invoking `send`
  failure-clause - a symbol, followed by zero or more expressions to be evaluated with the symbol bound to the failure response
  success-clause - as with failure-clause, but with the symbol bound to the body of the success response

  The body of either failure-clause or success-clause can be omitted; it defaults to the symbol, meaning that the
  failure or success body is simply returned.

  Example:

      (-> (c/new-request handler)
          (c/to :get :endpoint)
          (c/send)
          (c/then (success
                    (write-success-to-log success)
                    success)))

  The entire failure clause can also be omitted (as in the example)."
  ([channel success-clause]
   `(then ~channel (failure#) ~success-clause))
  ([channel failure-clause success-clause]
   `(then* (<! ~channel) ~failure-clause ~success-clause)))
