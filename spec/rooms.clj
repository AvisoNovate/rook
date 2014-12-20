(ns rooms
  (:import [javax.servlet.http HttpServletResponse])
  (:require [io.aviso.rook.utils :as utils]
            [ring.util.response :as r]))

(defn create
  [hotel-id resource-uri]
  (let [room-id 227]
    (->
      (utils/response HttpServletResponse/SC_CREATED {:id       room-id
                                                      :hotel-id hotel-id})
      (r/header "Location" (str resource-uri room-id)))))
