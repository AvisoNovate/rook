(ns betty
  (:require [io.aviso.rook.utils :as utils]))

(defn ^:sync show [id]
  (utils/response {:message (str id " is a very fine id!")}))
