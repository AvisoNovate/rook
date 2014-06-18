(ns validating
  (:require
    [schema.core :as s]
    [io.aviso.rook.utils :as utils]))

(defn create
  {:sync   true
   :schema {:name                     s/Str
            (s/optional-key :address) [s/Str]
            (s/optional-key :city)    s/Str}}
  [^:request-key params]
  (utils/response 200 (-> params keys sort)))
