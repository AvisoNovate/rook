(ns sample.dynamic-interceptors
  (:require [io.pedestal.interceptor :refer [interceptor]]))

(def endpoint-labeler
  ;; Note the distinction: this puts the meta data on the function itself, not the Var referencing the
  ;; function.
  ^:endpoint-interceptor-fn
  (fn [endpoint]
    (interceptor {:name ::endpoint-labeler
                  :leave (fn [context]
                           (assoc-in context
                                     [:response :headers "Endpoint"]
                                     (:endpoint-name endpoint)))})))
