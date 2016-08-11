(ns sample.hotels
  {:constraints {:hotel-id #"\d{6}"}})


(defn view-hotel
  {:rook-route [:get "/:hotel-id"]}
  [^:path-param hotel-id]
  {:handler ::view-hotel
   :hotel-id hotel-id})
