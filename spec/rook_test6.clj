(ns rook-test6)

(defn create
  {:route [:post [:user-name :password]]}
  [username password])
