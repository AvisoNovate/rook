(ns io.aviso.rook.schema-validation
  "Adds validation of incoming content, based on Prismatic Schema. Added to the Rook pipeline,
  this :schema meta-data key of the targetted function, if non-nil, is used to
  validate the incoming data."
  (:import (javax.servlet.http HttpServletResponse)
           (clojure.lang IPersistentMap))
  (:require
    [schema.core :as s]
    [io.aviso.rook.utils :as utils]))


(defn- identify-schema-keys
  [map-schema]
  (map s/explicit-schema-key (keys map-schema)))

(defprotocol DataSanitization
  "Applies to a Schema and a Data and produces a new Data that contains only keys that are appropriate to the Schema."
  (sanitize [schema data]))

(extend-protocol DataSanitization

  nil
  (sanitize [_ _] nil)

  ;; Non-matches are passed through unchanged.
  Object
  (sanitize [_ data] data)

  IPersistentMap
  (sanitize [schema data]
    (reduce-kv (fn [output k v]
                 (let [k' (s/explicit-schema-key k)]
                   (if (contains? data k')
                     (assoc output k' (sanitize v (get data k')))
                     output)))
               {}
               schema)))

(defn format-failures
  [failures]
  {:error "validation-error"
   ;; This needs work; it won't transfer very well to the client for starters.
   :failures (str failures)})

(defn validate-against-schema
  "Performs the validation and returns nil if no error, or a 400 response (with additional
  details in the body) if invalid."
  [{:keys [params]} schema]
  ;; TODO: possibly manipulate input to conform, as much as possible to the
  ;; schema. Challenge: extra keys should be ignored, not cause errors!
  (if-let [failures (s/check schema (sanitize schema params))]
    (utils/response HttpServletResponse/SC_BAD_REQUEST (format-failures failures))))

(defn wrap-with-schema-validation
  [handler]
  (fn [request]
    (or
      (when-let [schema (-> request :rook :metadata :schema)]
        (validate-against-schema request schema))
      (handler request))))

