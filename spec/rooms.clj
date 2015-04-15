(ns rooms
  "A set of rooms within a specific hotel."
  (:require [io.aviso.rook.utils :as utils]
            [ring.util.response :as r]
            [io.aviso.rook.schema :as rs]
            [schema.core :as s])
  (:import [javax.servlet.http HttpServletResponse]))

;; In a real app, we'd probably have a POST /hotels/:id/create-rooms instead.
;; But this exists mostly to


(rs/defschema RoomSize (s/enum :single :double :suite :special))

(rs/defschema CreateRoomRequest
  {:number s/Int
   :floor  s/Int
   :size   RoomSize})

(def create-responses
  {HttpServletResponse/SC_CREATED   {:id       s/Int
                                     :hotel_id s/Int}
   HttpServletResponse/SC_NOT_FOUND (rs/with-description "The specified hotel does not exist." nil)})

(defn create
  {:body-schema CreateRoomRequest
   :responses   create-responses}
  [hotel-id resource-uri]
  (let [room-id 227]
    (->
      (utils/response HttpServletResponse/SC_CREATED {:id       room-id
                                                      :hotel-id hotel-id})
      (r/header "Location" (str resource-uri room-id)))))

(rs/defschema RoomBooking
  "Identifies a range of dates a room was booked."
  {:start_at s/Inst
   :end_at   s/Inst
   :rating   (rs/with-description "Client's rating for their stay."
                                  (s/maybe s/Int))})

(rs/defschema ShowRoomResponse "A room within a hotel."
  {:number          s/Int
   :floor           s/Int
   :last_booked_at  (s/maybe s/Inst)
   :size            RoomSize
   :booking_history [RoomBooking]})

(def show-responses
  {HttpServletResponse/SC_OK        ShowRoomResponse
   HttpServletResponse/SC_NOT_FOUND nil})

(defn show
  "Displays a room in the hotel."
  {:responses show-responses}
  [hotel-id id])