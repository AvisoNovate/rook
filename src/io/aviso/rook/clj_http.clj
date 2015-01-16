(ns io.aviso.rook.clj-http
  "An optional implementation of a handler for use with [[io.aviso.rook.client]],
  based on [clj-http](https://github.com/dakrone/clj-http).

  This is largely for demonstration purposes.
  The implementation is restricted to sending and receiving
  EDN formatted data.

  In order to make use of this namespace, you must add clj-http to your classpath:"
  {:added "0.1.19"}
  (:import [javax.servlet.http HttpServletResponse])
  (:require [clojure.core.async :refer [thread]]
            [clojure.tools.logging :as l]
            [io.aviso.rook.utils :as utils]
            [io.aviso.toolchest.exceptions :refer [to-message]]
            [clj-http.client :as client]))


(defn- send-request-to-uri
  "Sends a request to an external URI. We make a lot of assumptions currently, including that the content
  can be EDN in both directions. Returns a channel which will get back a response map (that is, essentially,
  a Ring response map with extra keys)."
  [root-uri request]
  (let [body-params (:body-params request)
        body (if body-params (pr-str body-params))
        client-request (-> request
                           ;; Need to convert :body-params to :body
                           ;; and the :content-type :edn will take care of sending an EDN stream
                           ;; over the wire.
                           (dissoc :body-params :params)
                           ;; leave :query-params as-is
                           (assoc :url (str root-uri (:uri request))
                                  :body body
                                  :content-type :edn
                                  :accept :edn
                                  :follow-redirects false
                                  :throw-exceptions false
                                  :as :clojure))]
    (thread
      ;; I'd love it if there was a way to do this asynchronously via some kind of callback. We're using
      ;; a thread from the core.async pool AND a thread from the connection manager.
      (try
        (client/request client-request)
        (catch Throwable t
          (l/errorf t "Exception for outgoing request %s: %s"
                    (utils/summarize-request client-request)
                    (to-message t))

          ;; Instead of returning nothing, returning a 500, which is better for anything downstream.
          (utils/failure-response HttpServletResponse/SC_INTERNAL_SERVER_ERROR
                                  "failed-outgoing-request"
                                  (format "Request failure to `%s'." (:url client-request))))))))

(defn handler
  "Creates a request handler that accepts the request map (defined by [[io.aviso.rook.client]])
  and executes the request, returning a channel that will receive a Ring response map.

  root-uri
  : Root URI for requests using this handler. The Root URI should end with a slash.
    The [[io.aviso.rook.client/to]] function sets the :uri key to be the rest of the path.

  A new handler is created for each different root-uri."
  [root-uri]
  (partial send-request-to-uri root-uri))
