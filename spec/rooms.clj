(ns rooms
  "A set of rooms within a specific hotel."
  (:require [io.aviso.rook.utils :as utils]
            [ring.util.response :as r]
            [io.aviso.rook.schema :as rs]
            [schema.core :as s])
  (:import [javax.servlet.http HttpServletResponse]))

;; In a real app, we'd probably have a POST /hotels/:id/create-rooms instead.
;; But this exists mostly to
(defn create
  [hotel-id resource-uri]
  (let [room-id 227]
    (->
      (utils/response HttpServletResponse/SC_CREATED {:id       room-id
                                                      :hotel-id hotel-id})
      (r/header "Location" (str resource-uri room-id)))))

(rs/defschema Room "A room within a hotel."
  {:number         s/Int
   :floor          s/Int
   :last_booked_at s/Inst
   :size           (s/enum :single :double :suite :special)})

(def show-responses
  {HttpServletResponse/SC_OK        Room
   HttpServletResponse/SC_NOT_FOUND nil})

(defn show
  "Displays a room in the hotel."
  {:responses show-responses}
  [hotel-id id])