(ns validating
  (:require [schema.core :as s]
            [io.aviso.rook.utils :as utils]))

(defn ->vector [vm]
  (cond (vector? vm) vm
        (map? vm) (vals vm)
        :else []))

(defn index
  {:schema    {:tags [s/Str]}
   :coercions {[s/Str] ->vector}}
  [params]
  (utils/response 200 (-> params :tags sort)))

(defn create
  {:schema {:name                     s/Str
            (s/optional-key :address) [s/Str]
            (s/optional-key :city)    s/Str}}
  [params]
  (utils/response 200 (-> params keys sort)))
