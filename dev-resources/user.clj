(ns user
  (:use
    clojure.repl
    io.aviso.repl
    speclj.config)
  (:require
    ;; If not present, then test suite fails loading schema-validation with a ClassNotFound on EnumSchema
    ;; Leaky abstractions ...
    schema.core))

(install-pretty-exceptions)

(alter-var-root #'default-config assoc :color true :reporters ["documentation"])
