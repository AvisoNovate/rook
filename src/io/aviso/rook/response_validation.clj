(ns io.aviso.rook.response-validation
  "Allows for validation of the content of responses
  from resource handler functions. This is usually only enabled during development."
  {:since "0.1.11"}
  (:import (javax.servlet.http HttpServletResponse))
  (:require
    [clojure.tools.logging :as l]
    [schema.core :as schema]
    [io.aviso.rook.internals :as internals]
    [io.aviso.rook.utils :as utils]))

(defn ensure-matching-response*
  [response fn-name responses]
  (let [status (:status response)]
    (if-not (contains? responses status)
      (throw (ex-info (format "Response from %s was unexpected status code %d." fn-name status)
                      {:function  fn-name
                       :response  response
                       :responses responses})))

    ;; The schema may be nil to indicate an empty response (no body, just headers).
    ;; Since we just validate the body, there's no work to do. Perhaps we should
    ;; validate that the body is, in fact, empty.

    (if-let [response-schema (get responses status)]
      (if-let [failure (schema/check response-schema (:body response))]
        (throw (ex-info (format "Response validation failiure for %s: %s" fn-name (pr-str failure))
                        {:function        fn-name
                         :failure         failure
                         :response        response
                         :response-schema response-schema}))))
    response))

(defn ensure-matching-response
  "Given a response and a map from status code to a schema (for the body), ensures that the
  response matches, or converts the response into a SC_INTERNAL_SERVER_ERROR.

  response
  : The Ring response to be validated.

  fn-name
  : String name of the resource handler function, used for exeption reporting.

  responses
  : Map from status code to a schema. The response's status code must be a key here. A non-nil
    value is a schema used to validate the response."
  [response fn-name responses]
  (try
    (ensure-matching-response* response fn-name responses)
    (catch Throwable t
           (l/error t (internals/to-message t))
           (internals/throwable->failure-response t))))

(defn wrap-with-response-validation
  "Middleware to ensure that the response provided matches the :responses metadata on the resource handler function.

  handler
  : delegate handler

  metadata
  : metadata about the resource handler function

  enabled
  : _Default: true_
  : If false, then no validation occurs (which is sensible for producton mode)."
  ([handler metadata]
   (wrap-with-response-validation handler metadata true))
  ([handler metadata enabled]
   (if enabled
     (if-let [responses (:responses metadata)]
       (fn [request]
         (-> request
             handler
             (ensure-matching-response (:function metadata) responses)))))))