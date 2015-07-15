(ns hotels
  "The Hotels Resource."
  (:require [schema.core :as s]
            [io.aviso.rook.schema :as rs])
  (:import [javax.servlet.http HttpServletResponse]))

;; These are just placeholders for testing the Swagger integration:

(rs/defschema ShowHotelResponse
  {:id         (rs/with-description "Unique id for the hotel." s/Uuid)
   :created_at (rs/with-description "Instant when hotel was first created." s/Inst)
   :updated_at (rs/with-description "Instant when hotel was last updated." s/Inst)
   :name       (rs/with-description "The name of the hotel." s/Str)})


(rs/defschema IndexQuery
  {(s/optional-key :sort)       (rs/with-description
                                  "Indentified differnt ways the list of hotels may be sorted."
                                  (s/enum :created_at :updated_at :name))
   (s/optional-key :descending) (rs/with-description
                                  "Indicates that sorting (if any) should be in descending, not ascending, order."
                                  s/Bool)})

(def index-responses
  {HttpServletResponse/SC_OK (rs/with-description "List of matching hotels in specified order."
                                                  [ShowHotelResponse])})

(defn index
  "Returns a list of all hotels, with control over sort order."
  {:query-schema IndexQuery
   :responses    index-responses}
  [params]
  nil)

(def show-responses
  {HttpServletResponse/SC_OK        (rs/with-usage-description "The hotel matching the id, if found." ShowHotelResponse)
   HttpServletResponse/SC_NOT_FOUND (rs/description "No hotel with the provided id could be found.")})

(def ^:private hotel-id-description "Unique id of hotel.")

(defn show
  "Returns a single hotel, if found."
  {:responses show-responses}
  [^{:description hotel-id-description} id]
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
  [^{:description hotel-id-description} id
   params
   ^{:header      true
     :description "Used for optimistic locking."} if-match])
