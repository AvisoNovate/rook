(ns catch-all
  (:require [ring.util.response :as resp]))

(defn index
  {:route [:all []]}
  []
  (resp/response "Caught you!"))
