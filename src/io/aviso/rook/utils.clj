(ns io.aviso.rook.utils
  "Kitchen-sink of useful standalone utilities."
  (:import
    (java.util UUID)
    (javax.servlet.http HttpServletResponse))
  (:require
    [clojure.pprint :as pprint]))

(defn new-uuid
  "Generates a new UUID string, via UUIDrandomUUID."
  []
  (-> (UUID/randomUUID) .toString))


(defn response
  "Construct Ring response for success or other status."
  ([body] (response HttpServletResponse/SC_OK body))
  ([status body] {:status status :body body}))

(defn pretty-print
  "Pretty-prints the supplied object to a returned string."
  [object]
  (pprint/write object
                :stream nil
                :pretty true))

(defn pretty-print-brief
  "Prints the object more briefly, with limits on level and length."
  [object]
  (binding [*print-level* 4
                     *print-length* 5] (pretty-print object)))

