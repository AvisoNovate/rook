(ns io.aviso.rook.server
  "Utilities for creating Ring handlers."
  (:require [clojure.core.async :refer [alt! chan timeout >!]]
            [io.aviso.rook.async :as async]
            [io.aviso.rook.utils :as utils]
            [io.aviso.binary :refer [format-binary]]
            [io.aviso.toolchest.macros :refer [cond-let]]
            [io.aviso.toolchest.collections :refer [pretty-print]]
            [clojure.tools.logging :as l]
            [clojure.java.io :as io]
            [ring.util.response :as r]
            [medley.core :as medley]
            [clojure.string :as str])
  (:import [javax.servlet.http HttpServletResponse]
           [java.io ByteArrayOutputStream InputStream]))

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

(defn- read-bytes
  [stream length]
  (let [output-stream (ByteArrayOutputStream. (or length 2000))]
    (io/copy stream output-stream)
    (.toByteArray output-stream)))

(defn- format-bytes
  [label byte-array]
  (let [length (if (some? byte-array)
                 (alength byte-array)
                 0)]
    (if (zero? length)
      ""
      (format "%n%s (%,d bytes):%n%s"
              label
              length
              (format-binary byte-array :ascii true :line-bytes 40)))))

(defn- log-request-and-body [request ^bytes body-array]
  (l/debugf "Request %s:%n%s%s"
            (utils/summarize-request request)
            (->> (dissoc request :uri :request-method :body)
                 (medley/remove-vals nil?)
                 pretty-print)
            (format-bytes "Request Body" body-array)))

(defn wrap-debug-request
  "Logs a pretty-printed representation of each incoming request, at level debug. This includes output
  of the body's content in ASCII and hex; this means that the entire body (if present) may be consumed
  and stored in memory, and replaced with a new InputStream downstream (when debugging is enabled)."
  [handler]
  (fn [request]
    (cond-let
      (not (l/enabled? :debug))
      (handler request)

      [{:keys [^InputStream body content-length]} request]

      (nil? body)
      (do
        (log-request-and-body request nil)
        (handler request))

      (.markSupported body)
      (let [_ (.mark body Integer/MAX_VALUE)
            body-array (read-bytes body content-length)]
        (log-request-and-body request body-array)
        ;; Reset the stream back to its prior position before continuing.
        (.reset body)
        (handler request))

      :else
      (let [body-array (read-bytes body content-length)]
        (log-request-and-body request body-array)
        ;; We've consumed the :body of the request, must replace it with a new InputStream
        ;; before continuing further.
        (-> request
            (assoc :body (io/input-stream body-array))
            handler)))))

(def ^:private not-found-response
  (utils/response HttpServletResponse/SC_NOT_FOUND))

(defn- log-response
  "Logs the response at debug level (if enabled) with nice formatting; returns a new response (since reading
  an InputStream body is destructive)."
  [response]
  (cond-let
    (not (l/enabled? :debug))
    response

    [body (:body response)]

    (not (instance? InputStream body))
    (do
      (l/debugf "Response:%n%s" (pretty-print body))
      response)

    [mark-supported? (.markSupported body)
     _ (and mark-supported? (.mark body Integer/MAX_VALUE))

     content-length-str (get-in response [:headers "Content-Length"])

     content-length (if (not (str/blank? content-length-str))
                      (Integer/parseInt content-length-str))

     body-array (read-bytes body content-length)

     response' (dissoc response :body)]

    :else
    (do
      (l/debugf "Response:%n%s%s"
                (pretty-print response')
                (format-bytes "Response Body" body-array))
      ;; Mark is more commonly supported on the response side than on the request side.
      (if mark-supported?
        (do
          (.reset body)
          response))
      (assoc response :body (io/input-stream body-array)))))

(defn wrap-with-timeout
  "Asynchronous Ring middleware that applies a timeout to a request. When the timeout occurs, a 504 response is returned
  (and any later response from the downstream handler is ignored).

  In addition, the handler identifies unmatched requests and converts them to 404. This is important when using Jet
  as it does not have default behavior for a nil response (it throws an exception).

  This is obviously meant for asynchronous handlers only; it also includes logging of the response (as provided
  by [[wrap-debug-response]] for synchronous handlers)."
  {:added "0.1.21"}
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
                                    (log-response response))
                       handler-ch ([response]
                                    (if response
                                      (log-response response)
                                      (do
                                        (l/debugf "Handler for %s closed response channel." (utils/summarize-request request))
                                        not-found-response))))))))

(defn wrap-debug-response
  "Used with synchronous handlers to log the response sent back to the client at debug level.  [[wrap-with-timeout]]
  includes this functionality for asynchronous handlers."
  {:added "0.1.22"}
  [handler]
  (fn [request]
    (-> request
        handler
        log-response)))

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
  : enables logging (at debug level) of each incoming request via [[wrap-debug-request]]

  :log
  : enables logging of a summary of each incoming request (at info level) via [[wrap-log-request]]

  The extra logging and debugging middleware is added around the root handler (or the
  reloading handler that creates the root handler)."
  [{:keys [reload log debug]} creator & creator-args]
  (let [handler (if reload
                  (reloading-handler #(apply creator creator-args))
                  ;; Or just do it once, right now.
                  (apply creator creator-args))]
    (cond-> handler
            debug wrap-debug-request
            log wrap-log-request)))