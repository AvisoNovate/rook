(ns injection-demo
  (:require [io.aviso.rook.utils :as utils]))

(defn index
  [^:injection data-source]
  (utils/response {:data-source data-source}))
