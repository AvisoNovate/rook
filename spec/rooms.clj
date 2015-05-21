(ns rooms
  "A set of rooms within a specific hotel."
  (:require [io.aviso.rook.utils :as utils]
            [ring.util.response :as r]
            [io.aviso.rook.schema :as rs]
            [schema.core :as s])
  (:import [javax.servlet.http HttpServletResponse]))

;; In a real app, we'd probably have a POST /hotels/:id/create-rooms instead.
;; But this exists mostly to


(rs/defschema RoomSize
  "Size and/or layout of room."
  (s/enum :single :double :suite :special))

(rs/defschema CreateRoomRequest
  {:number (rs/with-description "Room number (unique on floor)." s/Int)
   :floor  (rs/with-description "Floor number containing the room." s/Int)
   :size   (rs/with-description "Size of room." RoomSize)})

(def create-responses
  {HttpServletResponse/SC_CREATED   (rs/with-description "The room was created succesfully."
                                                         {:id       (rs/with-description "Unique id for the new room." s/Int)
                                                          :hotel_id (rs/with-description "Unique id for the hotel containing the room." s/Int)})
   HttpServletResponse/SC_NOT_FOUND (rs/description "The specified hotel does not exist.")})

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
  {:start_at (rs/with-description "Time at which a booking begins." s/Inst)
   :end_at   (rs/with-description "Time at which a booking end." s/Inst)
   :rating   (rs/with-description "Client's rating for their stay."
                                  (s/maybe (s/both
                                             s/Int
                                             (s/pred pos? 'non-negative))))})

(rs/defschema ShowRoomResponse "A room within a hotel."
  {:number          (rs/with-description "Room number." s/Int)
   :floor           (rs/with-description "Floor on which room is situated." s/Int)
   :last_booked_at  (rs/with-description "Time of last booking, if any." (s/maybe s/Inst))
   :size            RoomSize
   :meta            (rs/with-description "Extra data about the room." {s/Keyword s/Str})
   :links           (rs/with-description "Links about the room." {(s/enum :facebook :twitter) s/Str})
   :booking_history (rs/with-description "History of bookings of the room." [RoomBooking])})

(def show-responses
  {HttpServletResponse/SC_OK        (rs/with-description "The room was located by the provided id." ShowRoomResponse)
   HttpServletResponse/SC_NOT_FOUND (rs/description "No room with the provided id exists.")})

(defn show
  "Displays a room in the hotel."
  {:responses show-responses}
  [hotel-id id])