(ns static2
  (:require [ring.util.response :as resp]))

(defn foo
  {:route [:get ["foo"]]}
  [foo]
  (resp/response (str "Here's the foo param for this request: " foo)))
