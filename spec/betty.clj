(ns betty
  (:require [io.aviso.rook.utils :as utils]))

(defn show [id]
  (utils/response {:message (str id " is a very fine id!")}))
