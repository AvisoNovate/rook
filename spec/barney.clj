(ns barney
  {:arg-resolvers {'partner (constantly :betty)}}
  (:require [io.aviso.rook.utils :as utils]
            [io.aviso.rook.client :as client]
            [clojure.core.async :as async]))

(defn index
  {:sync true}
  []
  (utils/response {:message "ribs!"}))

(defn show
  [id ^:request-key loopback-handler partner]
  (async/go
    (->
      (client/new-request loopback-handler)
      (client/to :get partner id)
      client/send
      (client/then
        (response
          (utils/response {:message (format "%s says `%s'" partner (:message response))}))))))
