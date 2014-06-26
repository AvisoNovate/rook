(ns creator-loopback
  (:require
    [io.aviso.rook
     [async :as async]
     [client :as c]]))

(defn create
  [^:request-key loopback-handler]
  (println "creator-loopback/create")
  (-> (c/new-request loopback-handler)
      (c/to :post :creator)
      c/send))
