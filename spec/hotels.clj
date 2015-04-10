(ns hotels
  "The Hotels Resource."
  (:require [schema.core :as s]
            [io.aviso.rook.schema :as rs])
  (:import [javax.servlet.http HttpServletResponse]))

;; These are just placeholders for testing the Swagger integration:

(rs/defschema Hotel
  "Standard hotel data."
  {:id         s/Uuid
   :created_at s/Inst
   :updated_at s/Inst
   :name       s/Str})


(rs/defschema IndexQuery
  {(s/optional-key :sort)       (s/enum :created_at :updated_at :name)
   (s/optional-key :descending) s/Bool})

(def index-responses
  {HttpServletResponse/SC_OK [Hotel]})

(defn index
  "Returns a list of all hotels, with control over sort order."
  {:query-schema IndexQuery
   :responses    index-responses}
  [params]
  nil)

(def show-responses
  {HttpServletResponse/SC_OK        Hotel
   HttpServletResponse/SC_NOT_FOUND nil})

(defn show
  "Returns a single hotel, if found."
  {:responses show-responses}
  [id]
  nil)

(rs/defschema ChangeBody
  "Allows the name of a hotel to be changed (when doing so does not conflict with an existing hotel)."
  {:name s/Str})

(def change-responses
  {HttpServletResponse/SC_NOT_FOUND nil
   HttpServletResponse/SC_CONFLICT  nil
   HttpServletResponse/SC_OK        nil})

(defn change
  "Updates a Hotel, to rename it. May result in a 409 Conflict if some other hotel has the same name."
  {:body-schema ChangeBody
   :responses   change-responses}
  [id params])
