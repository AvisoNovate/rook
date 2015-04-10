(ns io.aviso.rook.swagger

  "ALPHA / EXPERIMENTAL

  Adapter for ring-swagger. Converts an intermediate description of the namespaces
  into a description compatible with the ring-swagger library."
  (:require [schema.core :as s]
            [clojure.string :as str]
            [medley.core :as medley]
            [io.aviso.toolchest.macros :refer [cond-let]]
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

;; Revisit order of parameters in these for ease of ->> threading; should swagger-object always be the last?

(defn default-path-params-injector
  "Identifies each path parameter and adds a value for it to the swagger-object at the path defined by params-key."
  [swagger-options swagger-object routing-entry params-key]
  (let [path-ids (->> routing-entry :path (filter keyword?))
        reducer (fn [so path-id]
                  (update-in so params-key
                             conj {:name     path-id
                                   :in       :path
                                   :required :true}))]
    (reduce reducer swagger-object path-ids)))

(defn reduce-schema-key
  "Simplifies a key from a schema reducing it to a keyword, and whether the key is required or optional."
  [schema-key]
  (cond (s/optional-key? schema-key)
        [(.k schema-key) false]

        (s/required-key? schema-key)
        [(.k schema-key) true]

        (keyword? schema-key)
        [schema-key true]

        :else
        (throw (RuntimeException. (format "No idea what to do with query schema key %s." schema-key)))))

(defn default-query-params-injector
  "Identifies each query parameter via the :query-schema metadata and injects it."
  [swagger-options swagger-object routing-entry params-key]
  (if-let [schema (-> routing-entry :meta :query-schema)]
    ;; Assumes that the schema is a map.
    (try
      (let [reducer (fn [so [key-id key-schema]]
                      (cond-let
                        ;; Just ignore these, they are a convinience for the caller ("be generous in what you accept").
                        (= s/Any key-id)
                        so

                        [[k required] (reduce-schema-key key-id)]

                        :else
                        (update-in so params-key
                                   conj {:name     k
                                         :in       :query
                                         :required required})))]
        (reduce reducer swagger-object schema))
      (catch Exception e
        (throw (ex-info "Unable to convert routing entry into Swagger data."
                        routing-entry
                        e))))
    ; no :query-schema
    swagger-object))

(defn schema->swagger-schema
  [swagger-options schema]
  ;; To be done!
  (medley/remove-vals nil?
                      {:required    []
                       ;; :description is often provided by with-description, which overrides the normal docstring.
                       :description (or (-> schema meta :description)
                                        (-> schema meta :doc))
                       :properties  {}}))

(defn to-schema-reference
  "Converts a schema to a schema reference; the schema must be named.  Returns a tuple
  of the possibly updated swagger object and a string reference to the schema within the swagger object
  (as a path string)."
  ;; TODO: return the {:schema {"$ref" ...}} map instead?
  [swagger-options swagger-object schema]
  (cond-let
    [schema-name (s/schema-name schema)
     schema-ns (-> schema meta :ns)]

    (or (nil? schema-name) (nil? schema-ns))
    (throw (ex-info "Unable to create a Swagger schema from an anonymous Prismatic schema."
                    schema))

    ;; Avoid forward slash in the swagger name, as that's problematic.
    [swagger-name (str schema-ns \: schema-name)
     swagger-reference (str "#/definitions/" swagger-name)
     swagger-schema (get-in swagger-object [:definitions swagger-name])]

    (some? swagger-schema)
    [swagger-object swagger-reference]

    [new-schema (schema->swagger-schema swagger-options schema)
     swagger-object' (assoc-in swagger-object [:definitions swagger-name] new-schema)]

    :else
    [swagger-object' swagger-reference]))

(defn default-body-params-injector
  "Uses the :body-schema metadata to identify the body params."
  [swagger-options swagger-object routing-entry paths-key]
  (if-let [schema (-> routing-entry :meta :body-schema)]

    ;; Assumption: it's a map and named
    (let [[swagger-object' schema-reference] (to-schema-reference swagger-options swagger-object schema)]
      (update-in swagger-object' paths-key
                 conj {:name     :request-body
                       :in       :body
                       :required true
                       :schema   {"$ref" schema-reference}}))
    ; no schema:
    swagger-object))

(defn default-responses-injector
  "Uses the :responses metadata to identify possible responses."
  [swagger-options swagger-object routing-entry paths-key]
  (let [responses (-> routing-entry :meta :responses)
        reducer (fn [so [status-code schema]]
                  (let [schema-meta (meta schema)
                        description (or (:description schema-meta)
                                        (:doc schema-meta)
                                        "Documentation not provided.")]
                    (assoc-in so (concat paths-key [:responses (str status-code)]) {:description description})))]
    (reduce reducer swagger-object responses)))

(defn default-path-item-object-injector
  "Injects a PathItemObject based on a routing entry.

  The paths-key is a coordinate into the swagger-object where the PathItemObject should be created.

  Returns the modified swagger-object."
  [swagger-options swagger-object routing-entry paths-key]
  (let [{endpoint-meta :meta} routing-entry
        swagger-meta (:swagger endpoint-meta)
        description (or (:description swagger-meta)
                        (:doc endpoint-meta))
        summary (:summary swagger-meta)
        path-params-injector (:path-params-injector swagger-options default-path-params-injector)
        query-params-injector (:query-params-injector swagger-options default-query-params-injector)
        body-params-injector (:body-params-injector swagger-options default-body-params-injector)
        responses-injector (:responses-injector swagger-options default-responses-injector)
        params-key (concat paths-key ["parameters"])]
    (as-> swagger-object %
          (assoc-in % paths-key
                    (medley/remove-vals nil?
                                        {:description  description
                                         :summary      summary
                                         :operation-id (:function endpoint-meta)}))
          (path-params-injector swagger-options % routing-entry params-key)
          (query-params-injector swagger-options % routing-entry params-key)
          (body-params-injector swagger-options % routing-entry params-key)
          (responses-injector swagger-options % routing-entry paths-key))))

(defn default-route-injector
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
    and including :function as the string name of the endpoint.

  Returns the modified swagger-object."
  [swagger-options swagger-object routing-entry]
  (let [path-str (-> routing-entry :path path->str)
        ;; Ignoring :all for the moment.
        method-str (-> routing-entry :method name)
        pio-constructor (:path-item-object-injector swagger-options default-path-item-object-injector)]
    ;; Invoke the constructor for the path info. It may need to make changes to the :definitions, so we have
    ;; to let it modify the entire Swagger object ... but we help it out by providing the path
    ;; to where the PathInfoObject (which describes what Rook calls a "route").
    (pio-constructor swagger-options swagger-object routing-entry
                     [:paths path-str method-str])))

(defn- routing-entry->map
  [[method path _ endpoint-meta]]
  {:method method
   :path   path
   :meta   endpoint-meta})

(defn default-configurer
  "The configurer is passed the final swagger object and can make final changes to it. This implementation
  does nothing, returning the swagger object unchanged."
  [swagger-options swagger-object routing-entries]
  swagger-object)

(defn construct-swagger-object
  "Constructs the root Swagger object from the Rook options, swagger options, and the routing table
  (part of the result from [[construct-namespace-handler]])."
  [swagger-options routing-table]
  (let [{:keys [skeleton route-converter configurer]
         :or   {skeleton        default-swagger-skeleton
                route-converter default-route-injector
                configurer      default-configurer
                }} swagger-options
        routing-entries (->> routing-table
                             vals
                             (apply concat)
                             (map routing-entry->map)
                             ;; Endpoints with the :no-swagger meta data are ignored.
                             (remove #(-> % :meta :no-swagger)))]
    (as-> (reduce (partial route-converter swagger-options) skeleton routing-entries) %
          (configurer swagger-options % routing-entries))))