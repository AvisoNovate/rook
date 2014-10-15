(ns org.example.resources.counters)

(def counters (atom {"foo" 0 "bar" 0}))

; GET /counters
(defn index
    []
    {:status 200 :body (str @counters)})

; GET /counters/:id
(defn show
    [id]
    {:status 200 :body (str (@counters id))})

; POST /counters?id=:id
(defn create
    [^:param id]
    (swap! counters #(assoc % id (get % id 0)))
    {:status 200 :body (str (@counters id))})

; PUT /counters/:id/increment
(defn increment
    {:route [:put [:id "increment"]]}
    [id]
    (swap! counters #(assoc % id (inc (get % id 0))))
    {:status 200 :body (str (@counters id))})