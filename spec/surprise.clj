(ns surprise
  {:sync true}
  (:require [ring.util.response :as resp]))

(defn index [strange-injection]
  (resp/response (str "This is " strange-injection "!")))
