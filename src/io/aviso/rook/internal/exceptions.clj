(ns io.aviso.rook.internal.exceptions)

(defn to-message [^Throwable t]
  (or (.getMessage t)
      (-> t .getClass .getName)))
