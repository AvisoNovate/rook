(ns surprise-foo
  {:sync true}
  (:require [ring.util.response :as resp]))

(defn index [id]
  (resp/response (str "Surprise at id " id "!")))
