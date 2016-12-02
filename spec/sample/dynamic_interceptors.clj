(ns sample.dynamic-interceptors
  (:require [io.pedestal.interceptor :refer [interceptor]]))

(defn endpoint-labeler
  [endpoint]
  (let [{:keys [endpoint-name]} endpoint]
    (interceptor {:name ::endpoint-labeler
                  :leave (fn [context]
                           (assoc-in context
                                     [:response :headers "Endpoint"]
                                     endpoint-name))})))
