(ns user
  (:use
    clojure.repl
    io.aviso.repl
    io.aviso.logging
    speclj.config))

(install-pretty-exceptions)
(install-pretty-logging)
(install-uncaught-exception-handler)

(alter-var-root #'default-config assoc :color true :reporters ["documentation"])
