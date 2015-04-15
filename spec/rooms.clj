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

(rs/defschema CreateRequest {
                             :number s/Int
                             :floor  s/Int
                             :size   RoomSize
                             })

(def create-responses
  {HttpServletResponse/SC_CREATED   {:id       s/Int
                                     :hotel-id s/Int}
   HttpServletResponse/SC_NOT_FOUND (rs/with-description "The specified hotel does not exist." nil)})

(defn create
  {:body-schema CreateRequest
   :responses   create-responses}
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
   :size           RoomSize})

(def show-responses
  {HttpServletResponse/SC_OK        Room
   HttpServletResponse/SC_NOT_FOUND nil})

(defn show
  "Displays a room in the hotel."
  {:responses show-responses}
  [hotel-id id])