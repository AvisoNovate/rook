(ns io.aviso.rook.swagger

  "ALPHA / EXPERIMENTAL

  Adapter for ring-swagger. Converts an intermediate description of the namespaces
  into a description compatible with the ring-swagger library."
  (:require [schema.core :as s]
            [clojure.string :as str]
            [medley.core :as medley]
            [io.aviso.toolchest.collections :refer [pretty-print]]
            [clojure.tools.logging :as l]))

(def default-swagger-skeleton
  "A base skeleton for a Swagger Object (as per the Swagger 2.0 specification), that is further populated from Rook namespace
  and schema data."
  {
   :swagger     "2.0"
   :info        {:title   "<UNSPECIFIED>"
                 :version "<UNSPECIFIED>"}
   :paths       {}
   :definitions {}
   :responses   {}
   })

(defn keyword->path?
  [v]
  (if (keyword? v)
    (str \{ (name v) \})
    v))

(defn path->str
  "Converts a Rook path (sequence of strings and keywords) into a Swagger path; e.g. [\"users\" :id] to \"/users/{id}\"."
  [path]
  (->>
    path
    (map keyword->path?)
    (str/join "/")
    (str "/")))

(defn default-path-item-object-constructor
  [swagger-options swagger-object routing-entry paths-key]
  (let [{endpoint-meta :meta} routing-entry
        swagger-meta (:swagger endpoint-meta)
        description (or (:description swagger-meta)
                        (:doc endpoint-meta))
        summary (:summary swagger-meta)]
    (assoc-in swagger-object paths-key
              (medley/remove-vals nil?
                                  {:description description
                                   :summary summary
                                   :operation-id (:function endpoint-meta)}))))

(defn default-route-converter
  "The default route converter.  The swagger-options may contain an override of this function.

  swagger-options
  : As passed to [[construct-swagger-object]].

  swagger-object
  : The Swagger Object under construction.

  routing-entry
  : A map with keys :method, :path, and :meta.

  :method
  : A keyword for the method, such as :get or :post, but may also be :all.

  :path
  : A seq of strings and keywords; keywords identify path variables.

  :meta
  : The merged meta-data of the endpoint function, with defaults from the enclosing namespace,
    and including :function as the string name of the endpoint."
  [swagger-options swagger-object routing-entry]
  (l/debug "merging" routing-entry)
  (let [path-str (-> routing-entry :path path->str)
        method-str (-> routing-entry :method name)
        pio-constructor (:path-item-object-constructor swagger-options default-path-item-object-constructor)]
    (pio-constructor swagger-options swagger-object routing-entry
       ;; Ignoring :all right now.
       [:paths path-str method-str])

    )
  )

(defn- routing-entry->map
  [[method path _ endpoint-meta]]
  {:method method
   :path   path
   :meta   endpoint-meta})


(defn tap'
  [message data]
  (l/debugf "%s: %s" message (pretty-print data))
  data)


(defn construct-swagger-object
  "Constructs the root Swagger object from the Rook options, swagger options, and the routing table
  (part of the result from [[construct-namespace-handler]])."
  [swagger-options routing-table]
  (let [{:keys [skeleton route-converter]
         :or   {skeleton        default-swagger-skeleton
                route-converter default-route-converter}} swagger-options]
    (->> routing-table
         vals
         (apply concat)
         (map routing-entry->map)
         (tap' "routing entries")
         ;; Add the :no-swagger meta data to edit it out.
         (remove #(-> % :meta :no-swagger))
         (reduce (partial route-converter swagger-options) skeleton))))