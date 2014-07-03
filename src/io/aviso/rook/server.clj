(ns io.aviso.rook.server
  "Utilities for creating Ring handlers."
  (:require
    [io.aviso.rook.utils :as utils]
    [clojure.tools.logging :as l]))

(defn reloading-handler
  "Wraps a handler creator function such that the root handler is created fresh on each request;
  this is used when developing the application as it supports re-loading of code in a REPL."
  [creator]
  (fn [request]
    ((creator) request)))

(defn wrap-log-request
  "Logs incoming requests. Note that this only logs requests that arrive via Jetty; async loopback requests are not logged."
  [handler]
  (fn [request]
    (l/info (utils/summarize-request request))
    (handler request)))

(defn wrap-debug-request
  "Writes a pretty-printed representation of each incoming request."
  [handler]
  (fn [request]
    (l/debugf "Request:%n%s" (utils/pretty-print request))
    (handler request)))

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