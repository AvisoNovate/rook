(ns io.aviso.rook.async
  "Support for asynchronous Ring request handlers."
  (:use
    [clojure.core.async :only [go >!]])
  (:require
    [clojure.tools.logging :as l]))

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