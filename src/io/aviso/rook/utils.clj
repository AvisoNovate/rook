(ns io.aviso.rook.utils
  "Kitchen-sink of useful standalone utilities."
  (:import
    (java.util UUID))
  (:use
    [clojure.core.async :only [go >!]])
  (:require
    [clojure.pprint :as pprint]))

(defn new-uuid
  "Generates a new UUID string, via UUIDrandomUUID."
  []
  (-> (UUID/randomUUID) .toString))


(defn response
  "Construct Ring response for success or other status."
  ([body] (response 200 body))
  ([status body] {:status status :body body}))

(defn pretty-print
  "Pretty-prints the supplied object to a returned string."
  [object]
  (pprint/write object
                :stream nil
                :pretty true))

(defmacro try-go
  "Wraps the body in a go block and a try block. The try block will
  catch any throwable and put it into the exception channel. This allow a failure
  to be communicated out of an asynchronous process and back to some originator
  that can report it, or attempt recovery."
  [exception-ch & body]
  `(let [ch# ~exception-ch]
     (go
       (try
         ~@body
         (catch Throwable t#
           (>! ch# t#)
           ;; Re-throw the exception; that's the only way to get out of the go block
           ;; without returning a value.
           (throw t#))))))