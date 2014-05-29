(ns rook-stress-test.resources.quux
  (:require [ring.util.response :as resp]))

(defn index []
  (resp/response "(quux) Hello!"))

(defn show [id]
  (resp/response (str "(quux) Interesting id: " id)))

(defn create [x]
  (resp/response (str "(quux) Attempted to create " x)))
