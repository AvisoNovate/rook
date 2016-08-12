(ns sample.async-widgets
  (:require [ring.util.response :refer [response]]
            [clojure.core.async :refer [go]]))

(defn view-widget
  {:rook-route [:get "/:widget-id"]
   :interceptors [:elapsed-time]}
  [^:path-param widget-id]
  (go (response {:id widget-id})))
