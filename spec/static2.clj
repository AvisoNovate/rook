(ns static2
  {:sync true}
  (:require [ring.util.response :as resp]))

(defn foo
  {:route [:get ["foo"]]}
  [foo]
  (resp/response (str "Here's the foo param for this request: " foo)))
