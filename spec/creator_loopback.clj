(ns creator-loopback
  (:require
    [io.aviso.rook
     [async :as async]
     [client :as c]]))

(defn create
  [loopback-handler]
  (println "creator-loopback/create")
  (-> (c/new-request loopback-handler)
      (c/to :post :creator)
      (c/with-callback :success identity)
      (c/with-callback :failure identity)
      (c/send)))