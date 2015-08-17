(ns user
  (:use clojure.repl
        io.aviso.repl
        io.aviso.exception
        speclj.config)
  (:require [schema.core :as s]
            [io.aviso.logging :as logging]))

(install-pretty-exceptions)
(logging/install-pretty-logging)
(logging/install-uncaught-exception-handler)

(alter-var-root #'default-config assoc :color true :reporters ["documentation"])

(alter-var-root #'*default-frame-rules*
                conj [:name "speclj.running" :terminate])

(s/set-compile-fn-validation! true)
(s/set-fn-validation! true)