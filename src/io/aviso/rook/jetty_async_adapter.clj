(ns io.aviso.rook.jetty-async-adapter
  "A wrapper around ring.adapter.jetty which makes use of Jetty Continuations linked to core.async channels."
  (:import (org.eclipse.jetty.server Server Request)
           (org.eclipse.jetty.server.handler AbstractHandler)
           (javax.servlet.http HttpServletRequest HttpServletResponse)
           (org.eclipse.jetty.continuation ContinuationSupport Continuation))
  (:require
    [clojure.core.async :refer [go <!]]
    [ring.util.servlet :as servlet]
    [ring.adapter.jetty :as jetty]))

(defn customized-proxy-handler
  [handler]
  (proxy [AbstractHandler] []
    (handle [_ ^Request base-request request response]
      (let [request-map (-> (servlet/build-request-map request)
                            ;; The change:
                            (assoc ::http-servlet-request request
                                                          ::http-servlet-response response))
            response-map (handler request-map)]
        (when response-map
          (servlet/update-servlet-response response response-map)
          (.setHandled base-request true))))))

(let [proxy-handler-var (-> 'ring.adapter.jetty ns-map (get 'proxy-handler))]
  (alter-var-root proxy-handler-var (constantly customized-proxy-handler)))

;; Although we could move some of this code into customized-proxy-handler, that
;; would be problematic for any application which happens to load this namespace,
;; but doesn't actually run the server async. This is reasonable that a server
;; may run multiple instances of Jetty, some for asynchronous web services in Rook,
;; others as standard synchronous web servers or services.  The customized proxy handler
;; is benign if async is not actually used.

(defn- wrap-with-continuation
  [handler]
  (fn [request]
    (let [^HttpServletRequest req (::http-servlet-request request)
          ^HttpServletResponse res (::http-servlet-response request)
          ^Continuation continuation (ContinuationSupport/getContinuation req)]
      (.suspend continuation res)
      (go
        (let [response-map (-> request
                               (dissoc ::http-servlet-request ::http-servlet-response)
                               handler
                               <!)]
          (-> continuation
              .getServletResponse
              (servlet/update-servlet-response response-map))
          (.complete continuation)
          ;; go blocks must return non-nil
          true)))

    ;; Return nil right now, to prevent the proxy handler from sending an immediate response.
    nil))

(defn ^Server run-async-jetty
  "Start a Jetty webserver to serve the given asynchronous handler.

  The asychronous handler is wrapped in some Jetty-specific continuation logic
  and passed to the standard run-jetty."
  [handler options]
  (jetty/run-jetty (wrap-with-continuation handler) options))