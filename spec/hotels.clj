(ns hotels
  "The Hotels Resource."
  (:require [schema.core :as s]
            [io.aviso.rook.schema :as rs])
  (:import [javax.servlet.http HttpServletResponse]))

;; These are just placeholders for testing the Swagger integration:

(rs/defschema ShowHotelResponse
  "Describes a hotel."
  {:id         s/Uuid
   :created_at s/Inst
   :updated_at s/Inst
   :name       s/Str})


(rs/defschema IndexQuery
  {(s/optional-key :sort)       (s/enum :created_at :updated_at :name)
   (s/optional-key :descending) s/Bool})

(def index-responses
  {HttpServletResponse/SC_OK (rs/schema HotelList "List of matching hotels in specified order." [ShowHotelResponse])})

(defn index
  "Returns a list of all hotels, with control over sort order."
  {:query-schema IndexQuery
   :responses    index-responses}
  [params]
  nil)

(def show-responses
  {HttpServletResponse/SC_OK        (rs/with-description "The hotel matching the id, if found." ShowHotelResponse)
   HttpServletResponse/SC_NOT_FOUND (rs/description "No hotel with the provided id could be found.")})

(defn show
  "Returns a single hotel, if found."
  {:responses show-responses}
  [id]
  nil)

(rs/defschema ChangeHotelRequest
  "Allows the name of a hotel to be changed (when doing so does not conflict with an existing hotel)."
  {:name (rs/with-description "The new name for the hotel, which must be unique."
                              s/Str)})

(def change-responses
  {HttpServletResponse/SC_NOT_FOUND (rs/description "No hotel with the provided id was located.")
   HttpServletResponse/SC_CONFLICT  (rs/description "The hotel can not be renamed as another hotel has the same name.")
   HttpServletResponse/SC_OK        (rs/description "The hotel was succesfully updated.")})

(defn change
  "Updates a Hotel, to rename it. May result in a 409 Conflict if some other hotel has the same name."
  {:body-schema ChangeHotelRequest
   :responses   change-responses}
  [id params])
