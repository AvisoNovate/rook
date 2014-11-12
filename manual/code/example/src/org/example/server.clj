(ns org.example.server
  (:import
    [org.eclipse.jetty.server Server])
  (:require
    [ring.adapter.jetty :as jetty]
    [io.aviso.rook :as rook]
    [clojure.tools.logging :as l]))

(defn start-server
  "Starts a server on the named port, and returns a function that shuts it back down."
  [port]
  (let [handler (-> (rook/namespace-handler
                      ["counters" 'org.example.resources.counters])
                    rook/wrap-with-standard-middleware)
        ^Server server (jetty/run-jetty handler {:port port :join? false})]
    (l/infof "Listening on port %d." port)
    #(.stop server)))

(defn main
  []
  (start-server 8080))