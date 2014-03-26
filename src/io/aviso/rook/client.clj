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
    [io.aviso.rook [utils :as utils]]))

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
   ;; :success by default returns just the body of the response.
   ;; :failure by default eturns the entire response.
   ;; In both cases, a couple of headers are scrubbed before passing the response
   ;; through the callback.
   :callbacks {:success :body
               :failure identity}})

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

(defn- process-async-exception
  [request uuid exception]
  (l/errorf "%s exception during asynchronous processing: %s" uuid (-> exception .getMessage (or (-> exception .getClass .getName))))
  (process-async-response request uuid (utils/response 500 nil)))

(defn send
  "Sends the request asynchronously, using the web-service-handler provided to new-request. Returns a channel
  that will receive the result. When a response arrives from the handler, the correct callback is invoked.
  The value put into the result channel is the value of the response passed through the callback.

  Each request is assigned a UUID to its :request-id key; this is to faciliate easier tracing of the request
  and response in any logged output.

  Each request is provided with an :exception-ch; "
  [request]
  (let [uuid (utils/new-uuid)
        exception-ch (chan 1)
        ring-request (-> request
                         :ring-request
                         (assoc :request-id uuid :exception-ch exception-ch))
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
      (alt!
        exception-ch ([exception] (process-async-exception request uuid exception))
        response-ch ([response] (process-async-response request uuid response))
        ))))