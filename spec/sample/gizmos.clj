(ns sample.gizmos
  (:require [ring.util.response :refer [response]]))

(defn list-all
  {:rook-route [:get ""]
   :route-name ::index}
  []
  (response []))
