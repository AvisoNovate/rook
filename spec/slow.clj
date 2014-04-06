(ns slow
  (:require
    [clojure.core.async :refer [go timeout <!]]
    [io.aviso.rook.utils :as utils]))

(defn index
  []
  (go
    ;; Sleep for 200 ms (when the Jetty server was started with a 100 ms timeout).
    (-> (timeout 200) <!)
    (utils/response {:message "slow!"})))
