(ns user
  (:use
    clojure.repl
    io.aviso.repl
    speclj.config))

(install-pretty-exceptions)

(alter-var-root #'default-config assoc :color true :reporters ["documentation"])
