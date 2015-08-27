(ns user
  (:use clojure.repl)
  (:require [schema.core :as s]
            [speclj.config :as config]
            [io.aviso.logging :as logging]))

;; Pretty 0.1.19 as lein plugin doesn't do this yet:
(logging/install-pretty-logging)
(logging/install-uncaught-exception-handler)

(alter-var-root #'config/default-config assoc :color true :reporters ["documentation"])

(s/set-compile-fn-validation! true)
(s/set-fn-validation! true)