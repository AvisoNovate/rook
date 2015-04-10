(ns hotels
  "The Hotels Resource."
  (:import [javax.servlet.http HttpServletResponse])
  (:require [schema.core :as s]))


;; These are just placeholders for testing the Swagger integration:

(s/defschema Hotel
  {:id         s/Uuid
   :created_at s/Inst
   :updated_at s/Inst
   :name       s/Str})


(s/defschema IndexParams
  {(s/optional-key :sort)      (s/enum :created_at :updated_at :name)
   (s/optional-key :descening) s/Bool})

(def index-responses
  {HttpServletResponse/SC_OK [Hotel]})

(defn index
  "Returns a list of all hotels, with control over sort order."
  {:schema    IndexParams
   :responses index-responses}
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

(s/defschema ChangeParams
  {:name s/Str})

(def change-responses
  {HttpServletResponse/SC_NOT_FOUND nil
   HttpServletResponse/SC_CONFLICT  nil
   HttpServletResponse/SC_OK        nil})

(defn change
  "Updates a Hotel, to rename it. May result in a 409 Conflict if some other hotel has the same name."
  {:schema    ChangeParams
   :responses change-responses}
  [id params])
