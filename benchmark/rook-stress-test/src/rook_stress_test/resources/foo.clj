(ns rook-stress-test.resources.foo
  (:require [ring.util.response :as resp]))

(defn index []
  (resp/response "(foo) Hello!"))

(defn show [id]
  (resp/response (str "(foo) Interesting id: " id)))

(defn create [x]
  (resp/response (str "(foo) Attempted to create " x)))
