(ns rook-stress-test.resources.baz
  (:require [ring.util.response :as resp]))

(defn index []
  (resp/response "(baz) Hello!"))

(defn show [id]
  (resp/response (str "(baz) Interesting id: " id)))

(defn create [x]
  (resp/response (str "(baz) Attempted to create " x)))
