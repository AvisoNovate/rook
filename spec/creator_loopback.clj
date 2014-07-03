(ns creator-loopback
  (:require
    [io.aviso.rook
     [async :as async]
     [client :as c]]))

(defn create
  [^:injection loopback-handler]
  (-> (c/new-request loopback-handler)
      (c/to :post :creator)
      c/send))
