(ns fred
  ;; Used a non-standard prefix for rook, to demonstrate that the eval occurs
  ;; in the fred namespace, not somewhere where there's a rook alias already.
  {:arg-resolvers [(r/build-map-arg-resolver :partner :barney)]}
  (:use
    [clojure.core.async :only [go]])
  (:require
    [io.aviso.rook :as r]
    [io.aviso.rook
     [client :as c]
     [utils :as utils]]))

(defn index
  [loopback-handler partner]
  (go
    (-> (c/new-request loopback-handler)
        (c/to :get partner)
        c/send
        (c/then (response
                  (utils/response (format "%s says `%s'" partner response)))))))