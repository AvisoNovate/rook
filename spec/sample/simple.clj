(ns sample.simple
  (:require [ring.util.response :refer [response]]))

(defn all-items
  {:rook-route [:get ""]}
  []
  (response "all-items"))

(defn ignored-method
  [])
