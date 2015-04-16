(ns io.aviso.rook.schema-validation
  "Adds validation of incoming content, based on Prismatic Schema. Added to the Rook pipeline,
  this :schema meta-data key of the targetted function, if non-nil, is used to
  validate the incoming data."
  (:require
    [schema.core :as s]
    [schema.coerce :as coerce]
    [schema.utils :as su]
    [ring.middleware.keyword-params :as kp]
    [io.aviso.rook.internals :as internals]
    [io.aviso.rook.utils :as utils])
  (:import [javax.servlet.http HttpServletResponse]
           [java.text SimpleDateFormat DateFormat]
           [java.util TimeZone Date UUID]))

;; Sneaky access to a private function.
(def ^:private keyify-params (deref #'kp/keyify-params))

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

(defn- rebuild-request
  [request key validated]
  (let [request' (assoc request key validated)]
    (if (= key :params)
      request'
      (assoc request' :params (merge (:form-params request')
                                     (:query-params request')
                                     (:body-params request'))))))

(defn ^:no-doc coerce-and-validate
  "Performs the validation. The data is coerced using the string-cooercion-matcher (which makes sense
  as many of the values are provided as string query parameters, but need to be other types).

  Validation may either fail or succeed.  For a failure, an error response must be sent
  to the client. For success, the coerced parameters must be passed to the next handler.

  Returns a tuple:

  - on success, the tuple is `[nil request]`; that is, an updated request with specific key
    (and :params) updated
  - on failure, the tuple is `[failures]`, where failures are as output from Schema validation"
  [request key coercer]
  ;; The coercer will coerce and validate:
  (let [validated (-> request key (or {}) keyify-params coercer)]
    (if (su/error? validated)
      [(su/error-val validated)]
      [nil (rebuild-request request key validated)])))

(defn ^:no-doc schema->coercer
  [schema]
  (coerce/coercer schema string-coercion-matcher))

(defn- do-wrap
  [handler metadata metadata-key request-key]
  (if-let [schema (get metadata metadata-key)]
    (let [coercer  (schema->coercer schema)
          function (:function metadata)]
      (fn [request]
        (let [[failures new-request] (coerce-and-validate request request-key coercer)]
          (if failures
            (wrap-invalid-response function failures)
            (handler new-request)))))))

(def wrap-with-schema-validation
  "Wraps a handler with schema validation.
  Schema validation allows any of the following request keys to be coerced and validated
  using a Prismatic Schema:  :query-params, :form-params, :body-params, :params.

  For each of the request keys, there's a corresponding metadata key:  :query-schema,
  :form-schema, :body-schema, and :schema.

  These schema values are also incorporated into Swagger descriptions of the endpoint.

  Remember that the :params key is a merge of the other keys.  Use of the :schema
  metadata is discouraged (largely, because it doesn't provide enough data to create
  an accurate Swagger description).

  When a schema is present in the metadata, the corresponding request key is first
  keywordized, then coerced
  and validated.  On a validation failure, a [[failure-response]] is returned to the client.

  Otherwise, the request key is updated with the  validated data, and
  the :params key is rebuilt as the shallow merge of the other three keys.

  In a request, the order of processing is :query-params, :form-params, :body-params, and lastly
  :params.

  The various convention argument resolvers (params, _params, params*, _params) all operate
  on the :params key of the request."
  (internals/compose-middleware
    (do-wrap :schema :params)
    (do-wrap :body-schema :body-params)
    (do-wrap :form-schema :form-params)
    (do-wrap :query-schema :query-params)))

