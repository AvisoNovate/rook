(ns io.aviso.rook.utils
  "Kitchen-sink of useful standalone utilities."
  (:import
    (java.util UUID))
  (:use
    [clojure.core.async :only [go >!]])
  (:require
    [clojure.tools.logging :as l]
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
  catch any throwable and log it as an error, then return a status 500 response.

  The request is used when reporting the exception (it contains a :request-id
  key set by io.aviso.client/send)."
  [request & body]
  `(go
     (try
       ~@body
       (catch Throwable t#
         (let [r# ~request]
           (l/errorf t# "Exception processing request %s (%s `%s')"
                     (:request-id r#)
                     (-> r# :request-method name .toUpperCase)
                     (:uri r#)))
         {:status 500}))))