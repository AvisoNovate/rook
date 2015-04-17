(ns static
  (:require [ring.util.response :as resp]))

(defn index [^:header accept ^:request-key server-name]
  (resp/response
    (str "Server " server-name " has received a request for some " accept ".")))
