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
           (java.text SimpleDateFormat)
           (java.util TimeZone Date)))


(defn format-failures
  [failures]
  {:error    "validation-error"
   ;; This needs work; it won't transfer very well to the client for starters.
   :failures (str failures)})

;; Borrowed from clojure.instant:
(def ^:private thread-local-utc-date-format
  ;; SimpleDateFormat is not thread-safe, so we use a ThreadLocal proxy for access.
  ;; http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=4228335
  (let [gmt (TimeZone/getTimeZone "GMT")]
    (proxy [ThreadLocal] []
      (initialValue []
        (doto (SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
          (.setTimeZone gmt))))))

(defn parse-instant
  "Parses a date and time in ISO8601 format into an instant (a Date)."
  [date-str]
  (-> thread-local-utc-date-format
      .get
      (.parse date-str)))

(defn format-instant
  "Formats an instant into ISO8601 format."
  [^Date instant]
  (-> thread-local-utc-date-format
      .get
      (.format instant)))

;;; Would prefer to merge my own into what string-coercion-matcher provides,
;;; but see https://github.com/Prismatic/schema/issues/82
(def ^:private extra-coercions
  {s/Bool (coerce/safe #(Boolean/parseBoolean %))
   s/Inst (coerce/safe #(parse-instant %))})

(defn- string-coercion-matcher
  [schema]
  (or (extra-coercions schema)
      (coerce/string-coercion-matcher schema)))

(defn wrap-invalid-response [failures]
  (utils/response HttpServletResponse/SC_BAD_REQUEST (format-failures failures)))

(defn validate-against-schema
  "Performs the validation. The data is coerced using the string-cooercion-matcher (which makes sense
  as many of the values are provided as string query parameters, but need to be other types).

  Validation may either fail or succeed.  For a failure, an error response must be sent
  to the client. For success, the cooerced parameters must be passed to the next handler.

  Rreturns a tuple:
  On success, the tuple is [:valid request] (that is, an updated request with the params
  re-written).
  On failure, the tuple is [:invalid failures], where failures are an output from Schema validation."
  [request schema]
  (let [coercer (coerce/coercer schema string-coercion-matcher)
        params' (-> request :params coercer)]
    (if (su/error? params')
      [:invalid (su/error-val params')]
      [:valid (assoc request :params params')])))

(defn wrap-with-schema-validation
  "Wraps a handler with validation, which is triggered by the [:rook :metadata :schema] key in the
  request.

  The two-argument version includes a function used to wrap the bad request response;
  this is identity in the normal case (and is provided to support async processing)."
  ([handler]
   (wrap-with-schema-validation handler wrap-invalid-response))
  ([handler failure-handler]
   (fn [request]
     (or
       (when-let [schema (-> request :rook :metadata :schema)]
         (let [[valid? data] (validate-against-schema request schema)]
           (case valid?
             :valid (handler data)
             :invalid (failure-handler data))))
       (handler request)))))

