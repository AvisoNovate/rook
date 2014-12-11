(ns io.aviso.rook.jetty-async-adapter
  "A wrapper around ring.adapter.jetty which makes use of Jetty Continuations linked to core.async channels."
  (:import (org.eclipse.jetty.server Server Request)
           (org.eclipse.jetty.server.handler AbstractHandler)
           (javax.servlet.http HttpServletRequest HttpServletResponse)
           (org.eclipse.jetty.continuation ContinuationSupport Continuation))
  (:require
    [clojure.tools.logging :as l]
    [clojure.core.async :refer [go <! timeout alts! take! close!]]
    [io.aviso.rook.utils :as utils]
    [io.aviso.toolchest.collections :refer [pretty-print pretty-print-brief]]
    [ring.util
     [servlet :as servlet]
     [response :as r]]
    [ring.adapter.jetty :as jetty]))

(defn- customized-proxy-handler
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

;;; Monkey patch the private function. Yes, you can do this. No, you shouldn't have to.

(let [proxy-handler-var #'ring.adapter.jetty/proxy-handler]
  (alter-var-root proxy-handler-var (constantly customized-proxy-handler)))

;;; Although we could move some of this code into customized-proxy-handler, that
;;; would be problematic for any application which happens to load this namespace,
;;; but doesn't actually run the server async. This is reasonable in a server
;;; that runs multiple instances of Jetty, some for asynchronous web services in Rook,
;;; others as standard synchronous web servers or services.  The customized proxy handler
;;; is benign if async is not actually used.

(defn- send-async-response
  [^Continuation continuation response]
  (try
    (-> continuation
        .getServletResponse
        (servlet/update-servlet-response response))
    (.complete continuation)

    (catch Throwable t
      (l/errorf t "Unable to send asynchronous response %s to client."
                (pretty-print-brief response)))))

(defn- deliver-response [continuation response]
  (l/debugf "Asynchronous response:%n%s" (pretty-print response))
  (send-async-response continuation (or response {:status HttpServletResponse/SC_NOT_FOUND})))

(defn- deliver-timeout [continuation request response-ch timeout-ms]
  (close! response-ch)
  (l/warnf "Request %s timed out after %,d ms."
                            (utils/summarize-request request)
                            timeout-ms)
  (send-async-response continuation
                       (->
                         (utils/response HttpServletResponse/SC_GATEWAY_TIMEOUT
                                         "Processing of request timed out.")
                         (r/content-type "text/plain"))))

(defn- wrap-with-continuation
  [handler timeout-ms]
  (fn [request]
    (let [^HttpServletRequest req (::http-servlet-request request)
          ^HttpServletResponse res (::http-servlet-response request)
          ^Continuation continuation (ContinuationSupport/getContinuation req)]
      (.suspend continuation res)
      (let [timeout-ch (timeout timeout-ms)
            response-ch (-> request
                            (dissoc ::http-servlet-request ::http-servlet-response)
                            (assoc :timeout-ch timeout-ch)
                            handler)]
        (go
         (alt!
           response-ch ([resp _] (deliver-response continuation resp))
           timeout-ch  ([_ _] (deliver-timeout continuation request response-ch timeout-ms))))))

    ;; Return nil right now, to prevent the proxy handler from sending an immediate response.
    nil))

(defn ^Server run-async-jetty
  "Start a Jetty webserver to serve the given asynchronous handler.

  The asychronous handler is wrapped in some Jetty-specific continuation logic
  and passed to the standard run-jetty.

  All the standard Jetty adapter options are supported, as well as an additional one:

  :timeout-ms
  : Request timeout time, in milliseconds. Defaults to 10000 (10 seconds).

  If a request can not be processed before the timeout, then a 504 (Gateway Timeout) response
  is returned to the client. A true response that arrives after the timeout is simply discarded.

  The Ring request map passed to the handler will include an extra key, :timeout-ch; this is the timeout
  channel. It can be closed to trigger an early timeout, or used in coordination with other
  core.async primitives to ensure that processing of a request shuts down cleanly if a timeout does occur."
  [handler {timeout-ms :async-timeout :or {timeout-ms 10000} :as options}]
  (jetty/run-jetty (wrap-with-continuation handler timeout-ms) options))
