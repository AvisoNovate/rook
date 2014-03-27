(ns io.aviso.rook.client
  "Because a significant part of implementing a service is communicating with other services, a consistent
  client-library is useful. This client library is a simple DSL for assembling a partial Ring request,
  as well as specifying callbacks based on success, failure, or specific status codes.


  The implementation is largely oriented around sending the assembled Ring request to a function that
  handles the request asynchronously, returning a core.async channel to which the eventual response
  will be sent.

  Likewise, the API is opionated that the body of the request and eventual response be in EDN format."
  (:refer-clojure :exclude [send])
  (:use [clojure.core.async :only [go <! chan alt!]])
  (:require
    [clojure.tools.logging :as l]
    [clojure.string :as str]
    [io.aviso.rook
     [async :as async]
     [utils :as utils]]))

(defn new-request
  "New request through the handler.

  web-service-handler - a function that is passed a (partial) Ring request and returns the channel
  that will receive the Ring response. Internally, it is typically
  implemented using the core.async go or thread macros. "
  [web-service-handler]
  (assert web-service-handler)
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
  "Targets the request with a method and a URI. The URI is composed from the path;
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

(defn with-body
  [request body]
  (assoc-in request [:ring-request :body] body))

(defn with-parameters
  [request parameters]
  (update-in request [:ring-request :params] merge parameters))

(defn with-headers
  [request headers]
  (update-in request [:ring-request :headers] merge headers))

(defn with-callback
  "Adds or replaces a callback for the given status code. A callback of nil removes the callback.
  Instead of a specific numeric code, the keywords :success (for any 2xx status) or :failure (for
  any non-2xx status) can be provided ... but exact status code matches take precedence."
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
  (l/debug "process-async-response" response)
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
    (binding [*print-level* 4
              *print-length* 5]
      (l/debugf "%s response:%n%s"
                uuid
                (utils/pretty-print response)))
    ;; Invoke the callback; the result from the callback is the result of the go block.
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
  "Sends the request asynchronously, using the web-service-handler provided to new-request. Returns a channel
  that will receive the result. When a response arrives from the handler, the correct callback is invoked.
  The value put into the result channel is the value of the response passed through the callback.

  The default callbacks produce a vector where the first value is the failure response (the entire Ring response map)
  and the second value is the body of the success response (in which case, the first value is nil).

  The intent is to use destructuring to determine which case occured, and proceed accordingly.

  The then macro is useful for working with this result directly.

  Each request is assigned a UUID to its :request-id key; this is to faciliate easier tracing of the request
  and response in any logged output. "
  [request]
  (let [uuid (utils/new-uuid)
        ring-request (-> request
                         :ring-request
                         (assoc :request-id uuid))
        handler (:handler request)
        _ (assert (and (:request-method ring-request)
                       (:uri ring-request))
                  "No target (request method and URI) has been specified.")
        _ (l/debugf "%s - %s request to `%s'%nparameters: %s%nbody: %s"
                    uuid
                    (-> ring-request :request-method name .toUpperCase)
                    (-> ring-request :uri)
                    (-> ring-request :params utils/pretty-print)
                    (-> ring-request :body utils/pretty-print))
        response-ch (handler ring-request)]
    (go
      (process-async-response request uuid (<! response-ch)))))

(defn- make-body [symbol body]
  (cond
    (empty? body) symbol
    (= 1 (count body)) (first body)
    :else (cons 'do body)))

(defmacro then
  "The send function returns a channel from which the eventual result can be taken; this macro makes it easy to response
  to either the normal success or failure cases, as determined by the default callbacks. Then makes use of <! and can therefore
  only be used inside a go block.

  channel - the expression which produces the channel, e.g., the result of calling send
  failure - the symbol which will be assigned the failure response
  failure-body - evaluated when there's a failure; if omitted, defaults to returning the failure response
  success - the symbol which will be assigned the success body (from the Ring response)
  success-body - evaluated when there is no failure, defaults to returning the success body

  Example:
    (-> (c/new-request handler)
        (c/to :get :endpoint)
        (c/send)
        (c/then (success
                  (write-success-to-log success)
                  success)))

  The entire failure clause can be omitted (as in the example)."
  ([channel success-clause]
   `(then ~channel (failure#) ~success-clause))
  ([channel [failure & failure-body] [success & success-body]]
   (assert failure "No failure symbol was provided to the then macro")
   (assert success "No success symbol was provided to the then macro")
   `(let [[~failure ~success] (<! ~channel)]
      (if ~failure
        ~(make-body failure failure-body)
        ~(make-body success success-body)))))