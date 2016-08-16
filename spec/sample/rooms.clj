(ns sample.rooms
  (:require [ring.util.response :refer [response]]))

(defn rooms-index
  {:rook-route [:get ""]}
  [ ^:path-param hotel-id]
  (response {:hotel-id hotel-id
             :handler ::rooms-index}))
