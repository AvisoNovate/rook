(ns echo-params2
  {:sync true}
  (:require
    [ring.util.response :as r]))

(defn change
  "This actually fails, because there's no :as on the map."
  [id {:keys [email new-password]}]
  (assert new-password)
  (r/response {:id id :email email}))
