(ns io.aviso.rook.schema-validation
  "Adds validation of incoming content, based on Prismatic Schema. Added to the Rook pipeline,
  this :schema meta-data key of the targetted function, if non-nil, is used to
  validate the incoming data."
  (:require
    [schema.core :as s]
    [schema.coerce :as coerce]
    [schema.utils :as su]
    [io.aviso.rook.utils :as utils])
  (:import (javax.servlet.http HttpServletResponse)
           (java.text SimpleDateFormat DateFormat)
           (java.util TimeZone Date UUID)))


;; Borrowed from clojure.instant:
(def ^:private ^ThreadLocal thread-local-utc-date-format
  ;; SimpleDateFormat is not thread-safe, so we use a ThreadLocal proxy for access.
  ;; http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=4228335
  (let [gmt (TimeZone/getTimeZone "GMT")]
    (proxy [ThreadLocal] []
      (initialValue []
        ;; New SimpleDateFormat each time, since it isn't thread safe.
        (doto (SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
          (.setTimeZone gmt))))))

(defn- ^DateFormat get-utc-date-format
  []
  (.get thread-local-utc-date-format))

(defn parse-instant
  "Parses a date and time in ISO8601 format into an instant (a Date)."
  [date-str]
  (.parse (get-utc-date-format) date-str))

(defn format-instant
  "Formats an instant into ISO8601 format."
  [^Date instant]
  (.format (get-utc-date-format) instant))

(def ^:private string-coercions
  (merge coerce/+string-coercions+
         {s/Uuid (coerce/safe #(UUID/fromString %))
          s/Inst (coerce/safe #(parse-instant %))}))

(defn- string-coercion-matcher
  [schema]
  (or (string-coercions schema)
      (coerce/string-coercion-matcher schema)))

(defn ^:no-doc wrap-invalid-response
  [endpoint-name failures]
  (utils/failure-response HttpServletResponse/SC_BAD_REQUEST
                          "invalid-request-data"
                          (format "Request for endpoint `%s' contained invalid data: %s"
                                  endpoint-name
                                  (pr-str failures))))

(defn validate-against-schema
  "Performs the validation. The data is coerced using the string-cooercion-matcher (which makes sense
  as many of the values are provided as string query parameters, but need to be other types).

  Validation may either fail or succeed.  For a failure, an error response must be sent
  to the client. For success, the coerced parameters must be passed to the next handler.

  Returns a tuple:

  - on success, the tuple is `[nil request]`; that is, an updated request with the :params
  re-written
  - on failure, the tuple is `[failures]`, where failures are as output from Schema validation"
  [request schema]
  (let [coercer (coerce/coercer schema string-coercion-matcher)
        params' (-> request :params (or {}) coercer)]
    (if (su/error? params')
      [(su/error-val params')]
      [nil (assoc request :params params')])))

(defn wrap-with-schema-validation
  "Wraps a handler with validation, which is triggered by the :schema key in the
  metadata. "
  [handler {:keys [schema function]}]
  (when schema
    (fn [request]
      (let [[failures new-request] (validate-against-schema request schema)]
        (if failures
          (wrap-invalid-response function failures)
          (handler new-request))))))

