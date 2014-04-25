(ns echo-params
  {:sync true}
  (:require
    [ring.util.response :as r]))

(defn index
  [params]
  (r/response {:params-arg params}))

(defn show
  [{:keys [user-id new-password] :as params*}]
  (r/response {:user-id      user-id
               :new-password new-password}))

(defn update
  "This actually fails, because there's no :as on the map."
  [id {:keys [email new-password]}]
  (assert new-password)
  (r/response {:id id :email email}))