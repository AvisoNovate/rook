(ns failing)

(defn index
  {:sync true}
  []
  (throw (IllegalStateException. "Sync Handler Failiure")))
