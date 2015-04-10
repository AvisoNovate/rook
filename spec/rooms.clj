(ns rooms
  "A set of rooms within a specific hotel."
  (:import [javax.servlet.http HttpServletResponse])
  (:require [io.aviso.rook.utils :as utils]
            [ring.util.response :as r]))

;; In a real app, we'd probably have a POST /hotels/:id/create-rooms instead.
;; But this exists mostly to
(defn create
  [hotel-id resource-uri]
  (let [room-id 227]
    (->
      (utils/response HttpServletResponse/SC_CREATED {:id       room-id
                                                      :hotel-id hotel-id})
      (r/header "Location" (str resource-uri room-id)))))

(defn show
  "Displays a room in the hotel."
  [hotel-id id])