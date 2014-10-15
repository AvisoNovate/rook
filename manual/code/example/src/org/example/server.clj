(ns org.example.server
    (:require
        [ring.adapter.jetty :as jetty]
        [io.aviso.rook :as rook]
        [ring.middleware.head :as head]))

(defn start-server
    [port]
    (let [handler (-> (rook/namespace-handler
                       [["counters"] 'org.example.resources.counters])
                       rook/wrap-with-standard-middleware
                       head/wrap-head)]
        (jetty/run-jetty handler {:port port :join? false})))

(defn main []
    (println "Listening on port 8080.")
    (start-server 8080))