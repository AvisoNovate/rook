(ns user
  (:use
    clojure.repl
    io.aviso.repl
    io.aviso.exception
    io.aviso.logging
    speclj.config))

(install-pretty-exceptions)
(install-pretty-logging)
(install-uncaught-exception-handler)

(alter-var-root #'default-config assoc :color true :reporters ["documentation"])

(alter-var-root #'*default-frame-filter*
                (fn [default]
                  (fn [frame]
                    (if (-> frame :name (.startsWith "speclj."))
                      :terminate
                      (default frame)))))
