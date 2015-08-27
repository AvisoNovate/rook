(ns org.example.resources.counters
    (:require [ring.util.response :as r])
    (:import [javax.servlet.http HttpServletResponse]))

(def counters (atom {"foo" 0 "bar" 0}))

(defn index
  "Lists all counters.

  Mapped to the route [:get []] via naming convention for symbol 'index'."
  []
  (r/response @counters))

(defn show
  "Returns a specific counter's current value.

  Mapped to the route [:get [:id]] via the naming convention for symbol 'show'."
  [id]
  (let [count (@counters id)]
    (if (some? count)
      (r/response count)
      (r/not-found {:message (format "No counter `%s'." id)}))))

(defn create
  "Creates a new counter with a provided id.

  Mapped to the route [:post []] via the naming convention for symbol 'create'.

  The :param metadata on the id parameter indicates that the value is provided from
  the query parameter with the matching name."
  [^:param id]
  (swap! counters #(assoc % id (get % id 0)))
  (-> (r/response (@counters id))
      (r/status HttpServletResponse/SC_CREATED)))

(defn increment
  "Increments an existing counter.

  This demonstrates a style where an action name is appended after the id of the entity
  to affect.

  This is not an example of an endpoint for which there is a naming convention, so
  the :route metadata is required. Without it, this function would not be exposed
  as an endpoint at all."
  {:route [:put [:id "increment"]]}
  [id]
  (swap! counters #(update-in % [id] (fnil inc 0)))
  (r/response (@counters id)))