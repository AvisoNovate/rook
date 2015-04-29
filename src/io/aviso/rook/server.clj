(ns io.aviso.rook.server
  "Utilities for creating Ring handlers."
  (:require [io.aviso.rook.utils :as utils]
            [io.aviso.binary :refer [format-binary]]
            [io.aviso.toolchest.macros :refer [cond-let]]
            [io.aviso.toolchest.collections :refer [pretty-print]]
            [io.aviso.toolchest.exceptions :refer [to-message]]
            [clojure.tools.logging :as l]
            [clojure.java.io :as io]
            [medley.core :as medley]
            [clojure.string :as str]
            [io.aviso.tracker :as t]
            [io.aviso.rook :as rook])
  (:import [javax.servlet.http HttpServletResponse]
           [java.io ByteArrayOutputStream InputStream]))

(defn wrap-creator
  "Wraps a creator and arguments as a function of no arguments. If the creator fails, then
  the error is logged and a handler that always responds with a 500 status is returned."
  {:added "0.1.25"}
  [creator creator-args]
  (fn []
    (try
      (apply creator creator-args)
      (catch Throwable t
        (l/error t "Unable to construct handler.")
        (let [fixed-response (utils/failure-response HttpServletResponse/SC_INTERNAL_SERVER_ERROR
                                                     "handler-creation-failure"
                                                     (to-message t))]
          (constantly fixed-response))))))

(defn reloading-handler
  "Wraps a handler creator function such that the root handler is created fresh on each request;
  this is used when developing the application as it supports re-loading of code in a REPL without having to
  restart the embedded Jetty instance."
  [creator]
  (fn [request]
    ((creator) request)))

(defn lazy-handler
  "Wraps a handler creator function such that the root handler is created lazily on first access."
  {:added "0.1.28"}
  [creator]
  (let [handler (delay (creator))]
    (fn [request]
      (@handler request))))

(defn wrap-log-request
  "Logs incoming requests (as info), identifying method and URI."
  [handler]
  (fn [request]
    (l/info (utils/summarize-request request))
    (handler request)))

(defn wrap-track-request
  "An alternative to [[wrap-log-request]]; adds tracking with a label via [[summarize-request]]."
  {:added "0.1.24"}
  [handler]
  (fn [request]
    (t/track #(utils/summarize-request request)
             (handler request))))

(defn- read-bytes
  [stream length]
  (let [output-stream (ByteArrayOutputStream. (or length 2000))]
    (io/copy stream output-stream)
    (.toByteArray output-stream)))

(defn- format-bytes
  [label ^bytes byte-array]
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

    [^InputStream body' body
     mark-supported? (.markSupported body')
     _ (and mark-supported? (.mark body' Integer/MAX_VALUE))

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
          (.reset body')
          response))
      (assoc response :body (io/input-stream body-array)))))

(defn wrap-debug-response
  "Used to log the response sent back to the client at debug level."
  {:added "0.1.22"}
  [handler]
  (fn [request]
    (-> request
        handler
        log-response)))

(defn wrap-with-exception-catching
  "A simple exception catching and reporting wrapper.

   Establishes a tracking checkpoint (so it works very well with [[wrap-track-request]].

   Exceptions are converted to 500 responses in the standard [[failure-response]] format."
  {:added "0.1.26"}
  [handler]
  (fn [request]
    (try
      (t/checkpoint
        (handler request))
      (catch Throwable t
        (utils/failure-response HttpServletResponse/SC_INTERNAL_SERVER_ERROR "unexpected-exception" (to-message t))))))

(defn construct-handler
  "Constructs a root handler using a creator function.  Normally, the creator function
  is invoked immediately, and returns a Ring handler function. However, during development,
  to take advantage of code reloading, the creator will be invoked on each incoming request.

  To fully take advantage of REPL oriented development, you should pass the Var containing
  the creator function, e.g. `#'create-app-handler`.

  The optional creator-args are additional arguments to be passed to the creator function;
  these are usually configuration data or dependencies.

  The options map contains several flags:

  :reload
  : enables the above-described reloading of the handler.

  :lazy
  : if true (and :reload is false) then the handler is not created until first needed, rather
    then immediately on invoking this function.

  :debug
  : enables logging (at debug level) of each incoming request via [[wrap-debug-request]]
    and [[wrap-debug-response]]

  :log
  : enables logging of a summary of each incoming request (at info level) via [[wrap-log-request]].

  :track
  : enables tracking a summary of each incoming request via [[wrap-track-request]].

  :standard
  : Enables the standard Rook middleware, (see [[wrap-with-standard-middleware]]).

  :exceptions
  : Enables [[wrap-with-exception-catching]] to catch and report exceptions.

  The extra middleware is added around the root handler (or the
  reloading handler that creates the root handler)."
  [{:keys [reload log debug track standard exceptions lazy]} creator & creator-args]
  (let [creator' (wrap-creator creator creator-args)
        handler (cond
                  reload
                  (reloading-handler creator')

                  lazy
                  (lazy-handler creator')

                  :else
                  ;; Or just do it once, right now.
                  (creator'))]
    (cond-> handler
            exceptions wrap-with-exception-catching
            standard rook/wrap-with-standard-middleware
            debug wrap-debug-request
            debug wrap-debug-response
            log wrap-log-request
            track wrap-track-request)))