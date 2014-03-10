(ns io.aviso.rook-test5)

;;; It's a bit hard to ensure ordering, since the keys come out in hashing order of the ns-publics map.
;;; So this doesn't prove much unless get-available-paths does two sorts: first by name early,
;;; then by line, last.

(defn show-default
  {:path-spec "/default"}
  [])

(defn show [id])
