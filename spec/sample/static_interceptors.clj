(ns sample.static-interceptors
  (:require [io.pedestal.interceptor :refer [interceptor]]
            [ring.util.response :refer [response]]))

(require '[clojure.pprint :refer [pprint]])

(def add-elapsed-time
  (interceptor
    {:name ::add-elapsed-time
     ;; If we were really doing this, we'd add a start time key on enter
     :enter identity
     :leave (fn [context]
             #_ (pprint context)
              (assoc-in context [:response :headers "Elapsed-Time"] "35"))}))

(defn index
  {:rook-route [:get ""]
   :interceptors [add-elapsed-time]}
  []
  (response [:one :two :three]))
