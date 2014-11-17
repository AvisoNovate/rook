(ns echo-params
  {:sync true}
  (:require
    [ring.util.response :as r]))

(defn index
  [^:request-key params]
  (r/response {:params-arg params}))

(defn show
  [{:keys [user-id new-password] :as _params*}]
  (r/response {:user-id      user-id
               :new-password new-password}))
