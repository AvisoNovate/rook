(ns echo-params
  (:require
    [ring.util.response :as r]))

(defn index
  {:sync true}
  [params]
  (r/response {:params-arg params}))
