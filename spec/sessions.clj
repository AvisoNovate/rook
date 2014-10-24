(ns sessions
  "Used to test session storage in the a pure async server."
  {:sync true}
  (:require
   [io.aviso.rook.utils :as utils]
   [ring.util.response :as r]
   [clojure.tools.logging :as l]))

(defn store
  {:route [:post [:key :value]]}
  [key value request]
  (l/debugf "Storing key/value `%s' / `%s'" key value)
  (-> (utils/response {:result :ok})
      (assoc :session (-> request :session (assoc key value)))))

(defn retrieve
  {:route [:get [:key]]}
  [key request]
  (l/debugf "Retrieving key `%s'" key)
  ;; Not returning a :session key in the response means, "keep the original session as-is".
  (utils/response {:result (get-in request [:session key])}))