(ns barney
  {:arg-resolvers {:partner (fn [_] (constantly :betty))}}
  (:require [io.aviso.rook.utils :as utils]))

(defn index
  []
  (utils/response {:message "ribs!"}))

