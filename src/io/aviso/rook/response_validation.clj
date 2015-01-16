(ns io.aviso.rook.response-validation
  "Allows for validation of the content of responses
  from endpoint functions. Response status must match expected values, and response bodies
  can be validated against a Schema. This is usually only enabled during development."
  {:since "0.1.11"}
  (:import [javax.servlet.http HttpServletResponse])
  (:require [io.aviso.toolchest.exceptions :refer [to-message]]
            [clojure.tools.logging :as l]
            [schema.core :as schema]
            [io.aviso.toolchest.macros :refer [cond-let]]
            [ring.util.response :as r]
            [io.aviso.rook.utils :as utils]))

(defn ensure-matching-response
  "Given a response and a map from status code to a schema (for the body), ensures that the
  response matches, or converts the response into a SC_INTERNAL_SERVER_ERROR.

  response
  : The Ring response to be validated.

  fn-name
  : String name of the endpoint function, used for exeption reporting.

  responses
  : Map from status code to a schema. The response's status code must be a key here. A non-nil
    value is a schema used to validate the response."
  [response fn-name responses]
  (try
    (cond-let

      [status (:status response)]

      (nil? status)
      (throw (ex-info "Response did not include a status."
                      {:function  fn-name
                       :response  response
                       :responses responses}))

      ;; Any 5xx status goes through unchanged
      (<= 500 status 599)
      response

      (not (contains? responses status))
      (throw (ex-info (format "Response had unexpected status code %d." status)
                      {:function  fn-name
                       :response  response
                       :responses responses}))

      ;; The schema may be nil to indicate an empty response (no body, just headers).
      ;; Since we just validate the body, there's no work to do. Perhaps we should
      ;; validate that the body is, in fact, empty.

      [response-schema (get responses status)]
      (nil? response-schema)
      response

      [failure (schema/check response-schema (:body response))]

      failure
      (throw (ex-info (format "Response validation failure: %s" (pr-str failure))
                      {:function        fn-name
                       :failure         failure
                       :response        response
                       :response-schema response-schema}))

      :else
      response)
    (catch Throwable t
      (let [extended-message (format "Exception validating response from %s: %s" fn-name (to-message t))]
        (l/error t extended-message)
        (-> (utils/failure-response HttpServletResponse/SC_INTERNAL_SERVER_ERROR
                                    "invalid-response"
                                    extended-message))))))

(defn wrap-with-response-validation
  "Middleware to ensure that the response provided matches the :responses metadata on the endpoint function.
  The keys of the responses metadata are the status codes to match, the values are schemas to match against the body
  of the response (there is no validation of headers).

  A response schema may be nil, in which case there is no validation of the body (but the actual status code must be a key
  of the :responses metadata).

  A response in the 5xx range is not validated in anyway, as these represent failures within a endpoint function,
  or a downstream failure passed through the endpoint function.

  Response validation should generally be the final middleware in a endpoint function's pipeline, to ensure
  this isn't interference from other middleware in the pipeline (such as [[io.aviso.rook.schema-validation]]).

  handler
  : delegate handler

  metadata
  : metadata about the endpoint function

  enabled
  : _Default: true_
  : If false, then no validation occurs (which is sensible for production mode)."
  ([handler metadata]
    (wrap-with-response-validation handler metadata true))
  ([handler metadata enabled]
    (if enabled
      (if-let [responses (:responses metadata)]
        (fn [request]
          (-> request
              handler
              (ensure-matching-response (:function metadata) responses)))))))