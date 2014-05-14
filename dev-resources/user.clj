(ns user
  (:use
    clojure.repl
    io.aviso.repl
    speclj.config)
  (:require
    ;; See https://github.com/slagyr/speclj/issues/79
    speclj.run.standard))

(install-pretty-exceptions)

(alter-var-root #'default-config assoc :color true :reporters ["documentation"])
