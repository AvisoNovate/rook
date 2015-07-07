(ns org.example.resources.counters
  (:import
    [javax.servlet.http HttpServletResponse])
  (:require
    [ring.util.response :as r]))

(def counters (atom {"foo" 0 "bar" 0}))

(defn index
  "GET /counters

  Lists all counters."
  []
  (r/response @counters))

(defn show
  "GET /counters/:id

  Returns a specific counter's current value."
  [id]
  (let [count (@counters id)]
    (if (some? count)
      (r/response count)
      (r/not-found {:message (format "No counter `%s'." id)}))))

(defn create
  "POST /counters?id=:id"
  [^:param id]
  (swap! counters #(assoc % id (get % id 0)))
  (-> (r/response (@counters id))
      (r/status HttpServletResponse/SC_CREATED)))

(defn increment
  "PUT /counters/:id/increment

  Increments an existing counter."
  {:route [:put [:id "increment"]]}
  [id]
  (swap! counters #(update % id (fnil inc 0)))
  (r/response (@counters id)))