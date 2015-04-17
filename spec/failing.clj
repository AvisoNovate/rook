(ns failing)

(defn index
  []
  (throw (IllegalStateException. "Sync Handler Failure")))
