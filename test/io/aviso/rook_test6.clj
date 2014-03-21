(ns io.aviso.rook-test6)

(defn create
  {:path-spec [:post "/:user-name/:password"]}
  [username password])
