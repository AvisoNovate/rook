(ns barney
  (:require [io.aviso.rook.utils :as utils]))

(defn index
  {:sync true}
  []
  (utils/response {:message "ribs!"}))
