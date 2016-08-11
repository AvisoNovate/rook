(ns sample.rooms)

(defn rooms-index
  {:rook-route [:get ""]}
  [ ^:path-param hotel-id]
  {:hotel-id hotel-id
   :handler ::rooms-index})
