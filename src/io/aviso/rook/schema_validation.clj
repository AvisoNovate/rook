(ns io.aviso.rook.schema-validation
  "Adds validation of incoming content, based on Prismatic Schema. Added to the Rook pipeline,
  this :schema meta-data key of the targetted function, if non-nil, is used to
  validate the incoming data."
  (:require
    [schema.core :as s]
    [schema.coerce :as coerce]
    [schema.utils :as su]
    [io.aviso.rook.utils :as utils])
  (:import (javax.servlet.http HttpServletResponse)))


(defn format-failures
  [failures]
  {:error    "validation-error"
   ;; This needs work; it won't transfer very well to the client for starters.
   :failures (str failures)})

;;; Would prefer to merge my own into what string-coercion-matcher provides,
;;; but see https://github.com/Prismatic/schema/issues/82
(def ^:private extra-coercions {s/Bool (coerce/safe #(Boolean/parseBoolean %))})

(defn- string-coercion-matcher
  [schema]
  (or (extra-coercions schema)
      (coerce/string-coercion-matcher schema)))

(defn validate-against-schema
  "Performs the validation. The data is coerced using the string-cooercion-matcher (which makes sense
  as many of the values are provided as string query parameters, but need to be other types).

  Validation may either fail or succeed.  For a failure, an error response must be sent
  to the client. For success, the cooerced parameters must be passed to the next handler.

  Rreturns a tuple:
  On success, the tuple is [:valid request] (that is, an updated request with the params
  re-written).
  On failure, the tuple is [:invalid response]: a response to return to the client."
  [request schema]
  (let [coercer (coerce/coercer schema string-coercion-matcher)
        params' (-> request :params coercer)]
    (if (su/error? params')
      [:invalid (utils/response HttpServletResponse/SC_BAD_REQUEST (-> params' su/error-val format-failures))]
      [:valid (assoc request :params params')])))

(defn wrap-with-schema-validation
  "Wraps a handler with validation, which is triggered by the [:rook :metadata :schema] key in the
  request.

  The two-argument version includes a function used to wrap the bad request response;
  this is identity in the normal case (and is provided to support async processing)."
  ([handler]
   (wrap-with-schema-validation handler identity))
  ([handler wrap-invalid-response]
   (fn [request]
     (or
       (when-let [schema (-> request :rook :metadata :schema)]
         (let [[valid? data] (validate-against-schema request schema)]
           (case valid?
             :valid (handler data)
             :invalid (wrap-invalid-response data))))
       (handler request)))))

