(ns io.aviso.rook.server
  "Utilities for creating Ring handlers."
  (:require [clojure.core.async :refer [alt! chan timeout >!]]
            [io.aviso.rook.async :as async]
            [io.aviso.rook.utils :as utils]
            [io.aviso.toolchest.collections :refer [pretty-print]]
            [clojure.tools.logging :as l]
            [ring.util.response :as r])
  (:import (javax.servlet.http HttpServletResponse)))

(defn reloading-handler
  "Wraps a handler creator function such that the root handler is created fresh on each request;
  this is used when developing the application as it supports re-loading of code in a REPL without having to
  restart the embedded Jetty instance."
  [creator]
  (fn [request]
    ((creator) request)))

(defn wrap-log-request
  "Logs incoming requests (as info), identifying method and URI."
  [handler]
  (fn [request]
    (l/info (utils/summarize-request request))
    (handler request)))

(defn wrap-debug-request
  "Logs a pretty-printed representation of each incoming request, at level debug."
  [handler]
  (fn [request]
    (l/debugf "Request:%n%s" (pretty-print request))
    (handler request)))

(def ^:private not-found-response
  (utils/response HttpServletResponse/SC_NOT_FOUND))

(defn wrap-with-timeout
  "Asynchronous Ring middleware that applies a timeout to a request. When the timeout occurs, a 504 response is returned
  (and any later response from the downstream handler is ignored).

  In addition, the handler identifies unmatched requests and converts them to 404. This is important when using Jet
  as it does not have default behavior for a nil response (it throws an exception)."
  [handler timeout-ms]
  (fn [request]
    (async/safe-go request
                   (let [timeout-ch (timeout timeout-ms)
                         request' (assoc request :timeout-ch timeout-ch)
                         handler-ch (handler request')]
                     (alt!
                       timeout-ch (let [message (format "Processing of request %s timed out after %,d ms."
                                                        (utils/summarize-request request)
                                                        timeout-ms)
                                        response (->
                                                   (utils/response HttpServletResponse/SC_GATEWAY_TIMEOUT message)
                                                   (r/content-type "text/plain"))]
                                    (l/warn message)
                                    response)
                       handler-ch ([response]
                                    (if response
                                      (do
                                        (l/debugf "Response:%n%s" (pretty-print response))
                                        response)
                                      (do
                                        (l/debugf "Handler for %f closed response channel." (utils/summarize-request request))
                                        not-found-response))))))))

(defn construct-handler
  "Constructs a root handler using a creator function.  Normally, the creator function
  is invoked immediately, and returns a Ring handler function. However, during development,
  to take advantage of code reloading, the creator will be invoked on each incoming request.

  To fully take advantage of REPL oriented development, you should pass the Var containing
  the creator function, e.g. `#'create-app-handler`.

  The optional creator-args are additional arguments to be passed to the creator function;
  these are usually configuration data or dependencies.

  The options map contains three flags:

  :reload
  : enables the above-described reloading of the handler.

  :debug
  : enables logging of each incoming request.

  :log
  : enables a summary of each incoming request (method and path) to be logged. :log is implied if :debug is true.

  The extra logging and debugging middleware is added around the root handler (or the
  reloading handler that creates the root handler)."
  [{:keys [reload log debug]} creator & creator-args]
  (let [handler (if reload
                  (-> #(apply creator creator-args) reloading-handler)
                  (apply creator creator-args))]
    (cond-> handler
            debug wrap-debug-request
            (or debug log) wrap-log-request)))