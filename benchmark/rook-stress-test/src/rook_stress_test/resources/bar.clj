(ns rook-stress-test.resources.bar
  (:require [ring.util.response :as resp]))

(defn index []
  (resp/response "(bar) Hello!"))

(defn show [id]
  (resp/response (str "(bar) Interesting id: " id)))

(defn create [x]
  (resp/response (str "(bar) Attempted to create " x)))
