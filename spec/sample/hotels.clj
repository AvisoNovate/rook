(ns sample.hotels
  {:constraints {:hotel-id #"\d{6}"}}
  (:require [ring.util.response :refer [response]]))


(defn view-hotel
  {:rook-route [:get "/:hotel-id"]}
  [^:path-param hotel-id]
  (response {:handler ::view-hotel
             :hotel-id hotel-id}))
