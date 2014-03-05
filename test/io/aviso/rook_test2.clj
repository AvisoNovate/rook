(ns io.aviso.rook-test2)

(defn index [offset id]
  {:body (str "id=" id "&offset=" offset)})
