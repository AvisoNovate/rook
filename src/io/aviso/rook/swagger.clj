(ns io.aviso.rook.swagger

  "ALPHA / EXPERIMENTAL

  Generates a Swagger 2.0 API description from Rook namespace metadata.

  This is currently under heavy development and is likely to be somewhat unstable for a couple of releases."
  (:require [schema.core :as s]
            [io.aviso.rook.schema :refer [unwrap-schema]]
            [clojure.string :as str]
            [medley.core :as medley]
            [io.aviso.toolchest.macros :refer [cond-let]]
            [io.aviso.toolchest.collections :refer [pretty-print]]
            [io.aviso.tracker :as t]
            [io.aviso.rook.dispatcher :as dispatcher])
  (:import [schema.core EnumSchema Maybe Both Predicate AnythingSchema]
           [io.aviso.rook.schema IsInstance]
           [clojure.lang IPersistentMap IPersistentVector]))

(defn- remove-nil-vals
  [m]
  (medley/remove-vals nil? m))


(defn- merge-in-description
  [swagger-schema schema]
  (let [schema-meta (meta schema)
        description (or (:description schema-meta)
                        (:doc schema-meta))]
    (if description
      (assoc swagger-schema :description description)
      swagger-schema)))

(def default-swagger-template
  "A base skeleton for a Swagger Object (as per the Swagger 2.0 specification), that is further populated from Rook namespace
  and schema data."
  {
   :swagger     "2.0"
   :paths       (sorted-map)
   :definitions (sorted-map)
   :schemes     ["http" "https"]
   :consumes    ["application/json" "application/edn"]
   :produces    ["application/json" "application/edn"]
   :info        {:title   "<UNSPECIFIED>"
                 :version "<UNSPECIFIED>"}})

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
        reducer  (fn [so path-id]
                   (update-in so params-key
                              conj {:name     path-id
                                    :type     :string       ; may add something later to refine this
                                    :in       :path
                                    :required true}))]
    (reduce reducer swagger-object path-ids)))

(defn analyze-schema-key
  "Simplifies a key from a schema reducing it to a keyword (or, to support s/Str or s/Keyword as a key,
  the special key ::wildcard), and whether the key is required or optional."
  [schema-key]
  (try
    ;; We're treating these special keys as optional, which seems to align with Prismatic, e.g.,
    ;; (s/check {s/Str s/Any} {}) => nil
    (cond-let
      (or (= s/Keyword schema-key)
          (= s/Str schema-key)
          ;; assumes that it is like UUID, convertable to/from string
          (instance? Class schema-key))
      [::wildcard false]

      [unwrapped (if (s/optional-key? schema-key)
                   (unwrap-schema schema-key)
                   schema-key)]

      ;; Handling a key that is an enum type is tricky. We return the
      ;; seq of potential keys and indicate that they are optional;
      ;; this will inject a number of keys into the Swagger Schema.
      (instance? EnumSchema unwrapped)
      [(unwrap-schema unwrapped) (s/required-key? schema-key)]
      :else
      [(s/explicit-schema-key schema-key) (s/required-key? schema-key)])
    (catch Throwable t
      (throw (ex-info "Unable to expand schema key."
                      {:schema-key schema-key}
                      t)))))

(declare ->swagger-schema simple->swagger-schema)

(defprotocol SchemaConversion
  "Converts a particular Prismatic Schema type to a Swagger schema."

  (convert-schema [schema swagger-options swagger-object]
    "Given a particular implementation of Prismatic Schema, do a conversion to a Swagger/JSON schema.
    Return a tuple of the (possibly updated swagger-object) and the converted schema."))

(extend-protocol SchemaConversion

  Object
  (convert-schema [schema _ _]
    (throw (ex-info "Unable to convert schema to Swagger."
                    {:schema-class (type schema)
                     :schema       schema})))

  EnumSchema
  (convert-schema [schema _ swagger-object]
    [swagger-object (merge-in-description
                      {:type :string                        ; an assumption
                       :enum (unwrap-schema schema)}
                      schema)])

  Maybe
  (convert-schema [schema swagger-options swagger-object]
    (let [[swagger-object' swagger-schema] (simple->swagger-schema swagger-options swagger-object (unwrap-schema schema))]
      [swagger-object' (-> swagger-schema
                           (assoc :allowEmptyValue true)
                           (merge-in-description schema))]))

  AnythingSchema
  (convert-schema [schema swagger-options swagger-object]
    [swagger-object (merge-in-description {:type :object} schema)])

  Both
  (convert-schema [schema swagger-options swagger-object]
    ;; Hopefully not doing anything too tricky here, we'll merge together the results of each nested Schema.
    (let [reducer (fn [[so-1 swagger-schema] nested-schema]
                    (let [[so-2 new-schema] (simple->swagger-schema swagger-options so-1 nested-schema)]
                      [so-2 (merge swagger-schema new-schema)]))]
      (reduce reducer [swagger-object] (unwrap-schema schema))))

  Predicate
  (convert-schema [_ _ swagger-object]
    ;; In the future, this may potentially set some of the Swagger schema options, such as
    ;; maximum or maxLength. For now it is a place holder to prevent an exception.
    [swagger-object])

  IsInstance
  (convert-schema [schema swagger-options swagger-object]
    (simple->swagger-schema swagger-options swagger-object (unwrap-schema schema)))

  IPersistentVector
  (convert-schema [schema swagger-options swagger-object]
    (if-not (= 1 (count schema))
      (throw (ex-info (format "Expected exactly one element in vector schema, not %d."
                              (count schema))
                      {:schema schema})))
    (t/track
      "Converting vector schema."
      (let [item-schema (first schema)
            [swagger-object' item-reference] (simple->swagger-schema swagger-options swagger-object item-schema)]
        [swagger-object' (merge-in-description {:type  :array
                                                :items item-reference}
                                               schema)])))

  IPersistentMap
  (convert-schema [schema swagger-options swagger-object]
    (t/track
      "Converting map schema."
      (->swagger-schema swagger-options swagger-object schema))))

(defn- simple->swagger-schema
  "Converts a simple (non-object) Schema into an inline Swagger Schema, with keys :type and perhaps :format, etc."
  [swagger-options swagger-object schema]
  (cond-let

    [data-type-mappings (:data-type-mappings swagger-options)
     data (get data-type-mappings schema)]

    (some? data)
    [swagger-object (merge-in-description data schema)]

    :else
    (convert-schema schema swagger-options swagger-object)))

(defn default-query-params-injector
  "Identifies each query parameter via the :query-schema metadata and injects it."
  [swagger-options swagger-object routing-entry params-key]
  (if-let [schema (-> routing-entry :meta :query-schema)]
    ;; Assumes that the schema is a map.
    (let [reducer (fn [so [key-id key-schema]]
                    (cond-let
                      ;; Just ignore these, they are a convinience for the caller ("be generous in what you accept").
                      (= s/Any key-id)
                      so

                      [[k required] (analyze-schema-key key-id)]

                      :else
                      (t/track
                        #(format "Describing query parameter `%s'." (name k))
                        (let [[swagger-object' swagger-schema] (simple->swagger-schema swagger-options so key-schema)
                              full-description (-> swagger-schema
                                                   (assoc :name k
                                                          :in :query
                                                          :required required)
                                                   (merge-in-description key-schema))]
                          (update-in swagger-object' params-key
                                     conj full-description)))))]
      (reduce reducer swagger-object schema))
    ; no :query-schema
    swagger-object))

(defn map->swagger-schema
  [swagger-options swagger-object schema]
  (letfn [(reducer [[acc-so acc-schema :as acc] [schema-key schema-value]]
                   ;; An s/Any as a key exists to "be generous in what you accept" and can be ignored.
                   (if (= s/Any schema-key)
                     acc
                     (cond-let
                       [[k required?] (analyze-schema-key schema-key)]

                       ;; If not a keyword, the other option is some sequence of keywords from an enum.
                       ;; We have to work pretty hard to get those options in ... partly because Swagger Schema
                       ;; is a bit obtuse, and partly because we probably need a kind of intermediate representation.
                       (not (keyword? k))
                       (let [k'      (if required?
                                       k
                                       (map s/optional-key k))
                             schema' (zipmap k' (repeat schema-value))]
                         (reduce reducer acc schema'))

                       [key-name (if (= ::wildcard k)
                                   "<any>"
                                   (name k))]

                       :else
                       (t/track
                         #(format "Describing key `%s'." key-name)
                         (let [[acc-so' swagger-schema] (simple->swagger-schema swagger-options acc-so schema-value)
                               path (if (= ::wildcard k)
                                      [:otherProperties]
                                      [:properties k])]
                           [acc-so' (cond-> (assoc-in acc-schema path swagger-schema)
                                            required? (update-in [:required] conj k))])))))]
    (let [base (remove-nil-vals {:description (or (-> schema meta :description)
                                                  (-> schema meta :doc))})]
      (reduce reducer [swagger-object base] schema))))

;; Can we merge simple->swagger-schema and ->swagger-schema?

(defn ->swagger-schema
  "Converts a Prismatic schema to a Swagger schema (or possibly, a \"$ref\" map pointing to a schema). A $ref
  will occur when the schema has the :name and :ns metadata, otherwise it will be inlined.

  nil or s/Any returns nil.

  Typically, the result of this is wrapped inside map with key :schema."
  [swagger-options swagger-object schema]
  (cond-let
    (or (nil? schema) (= s/Any schema))
    [swagger-object nil]

    (vector? schema)
    (let [[so' item-reference] (->swagger-schema swagger-options swagger-object (first schema))]
      [so' {:type  :array
            :items item-reference}])

    [schema-name (s/schema-name schema)
     schema-ns (-> schema meta :ns)]

    ;; Missing this stuff?  Make it anonymous.
    (or (nil? schema-name)
        (nil? schema-ns))
    ;; Nested schemas may be named, however, and will need to write into the swagger-object :definitions
    ;; map.
    (let [[so' new-schema] (map->swagger-schema swagger-options swagger-object schema)]
      [so' new-schema])

    ;; Avoid forward slash in the swagger name, as that's problematic, especially for
    ;; the swagger-ui (which must be taking a few liberties or shortcuts).
    [swagger-name (str schema-ns \: schema-name)
     swagger-reference {"$ref" (str "#/definitions/" swagger-name)}
     swagger-schema (get-in swagger-object [:definitions swagger-name])]

    (some? swagger-schema)
    [swagger-object swagger-reference]

    [[so-1 new-schema] (map->swagger-schema swagger-options swagger-object schema)
     so-2 (assoc-in so-1 [:definitions swagger-name] new-schema)]

    :else
    [so-2 swagger-reference]))

(defn default-body-params-injector
  "Uses the :body-schema metadata to identify the body params."
  [swagger-options swagger-object routing-entry paths-key]
  (t/track
    "Describing body parameters."
    (if-let [schema (-> routing-entry :meta :body-schema)]

      ;; Assumption: it's a map and named
      (let [[swagger-object' schema-reference] (->swagger-schema swagger-options swagger-object schema)]
        (update-in swagger-object' paths-key
                   conj {:name     (:body-name swagger-options)
                         :in       :body
                         :required true
                         :schema   schema-reference}))
      ; no schema:
      swagger-object)))

(defn default-responses-injector
  "Uses the :responses metadata to identify possible responses."
  [swagger-options swagger-object routing-entry paths-key]
  (let [responses (-> routing-entry :meta :responses)
        decorator (:response-decorator swagger-options)
        reducer   (fn [so [status-code schema]]
                    (t/track
                      #(format "Describing %d response." status-code)
                      (let [schema-meta (meta schema)
                            description (or (:description schema-meta)
                                            (:doc schema-meta)
                                            ;; description is required in the Response Object, so we need some default here.
                                            "Documentation not provided.")
                            [so' schema-reference] (->swagger-schema swagger-options so schema)
                            response    (->> {:description description
                                              :schema      schema-reference}
                                             remove-nil-vals
                                             (decorator swagger-options so routing-entry status-code schema))]
                        (assoc-in so' (concat paths-key [:responses status-code]) response))))]
    (reduce reducer swagger-object responses)))

(defn default-operation-decorator
  "Decorates a PathItemObject (which describes a single Rook endpoint) just before it is added to the Swagger object.
  This implementation returns it unchanged."
  [swagger-options swagger-object routing-entry path-item-object]
  path-item-object)

(defn default-operation-injector
  "Injects an Object object based on a single routing entry.

  The paths-key is a coordinate into the swagger-object where the PathItemObject should be created.

  Returns the modified swagger-object."
  [swagger-options swagger-object routing-entry paths-key]
  (let [{endpoint-meta :meta} routing-entry
        swagger-meta (:swagger endpoint-meta)
        description  (or (:description swagger-meta)
                         (:doc endpoint-meta))
        summary      (:summary swagger-meta)
        {:keys [path-params-injector query-params-injector body-params-injector responses-injector
                operation-decorator]} swagger-options
        params-key   (concat paths-key ["parameters"])]
    (as-> swagger-object %
          (assoc-in % paths-key
                    (remove-nil-vals {:description description
                                      :summary     summary
                                      ;; This is required inside a Operation object:
                                      :responses   (sorted-map)
                                      ;; There are a couple of scnearious where the function name is not
                                      ;; unique, alas. But that doesn't seem to cause problems.
                                      :operationId (:function endpoint-meta)}))
          (path-params-injector swagger-options % routing-entry params-key)
          (query-params-injector swagger-options % routing-entry params-key)
          (body-params-injector swagger-options % routing-entry params-key)
          (responses-injector swagger-options % routing-entry paths-key)
          (update-in % paths-key (fn [operation]
                                   (operation-decorator swagger-options % routing-entry operation))))))

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
  (t/track
    #(format "Describing endpoint %s `/%s'."
             (-> routing-entry :method name .toUpperCase)
             (->> routing-entry :path (str/join "/")))
    (let [path-str           (-> routing-entry :path path->str)
          ;; Ignoring :all for the moment.
          method-str         (-> routing-entry :method name)
          operation-injector (:operation-injector swagger-options)]
      ;; Invoke the constructor for the path info. It may need to make changes to the :definitions, so we have
      ;; to let it modify the entire Swagger object ... but we help it out by providing the path
      ;; to where the PathInfoObject (which describes what Rook calls a "route").
      (operation-injector swagger-options swagger-object routing-entry
                          [:paths path-str method-str]))))

(defn- routing-entry->map
  [[method path _ endpoint-meta]]
  {:method method
   :path   path
   :meta   endpoint-meta})

(defn- expand-method-all
  [{:keys [method] :as routing-entry}]
  (if-not (= :all method)
    [routing-entry]
    (for [m dispatcher/supported-methods]
      (assoc routing-entry :method m))))

(defn default-configurer
  "The configurer is passed the swagger options, the final Swagger object, and the seq of routing entries,
   and can make final changes to it. This implementation does nothing, returning the Swagger object unchanged."
  [swagger-options swagger-object routing-entries]
  swagger-object)

(defn default-response-decorator
  "A callback to decorate a specific response object just before it is stored into the swagger-object."
  [swagger-options swagger-object routing-entry status-code response-schema response-object]
  response-object)

(def default-data-type-mappings
  {s/Int     {:type :integer}
   s/Keyword {:type :string}
   Integer   {:type :integer :format :int32}
   Long      {:type :integer :format :int64}
   s/Num     {:type :number}
   Float     {:type :number :format :float}
   Double    {:type :number :format :double}
   s/Str     {:type :string}
   Byte      {:type :string :format :byte}
   s/Bool    {:type :boolean}
   s/Inst    {:type :string :format :date-time}
   s/Uuid    {:type :string :format :uuid}})

(def default-swagger-options
  {:template                       default-swagger-template
   :path                           []
   ;; Name of special parameter used for the body of the request
   :body-name                      :body
   :routing-entry-remove-predicate (constantly false)
   :route-injector                 default-route-injector
   :configurer                     default-configurer
   :data-type-mappings             default-data-type-mappings
   :operation-injector             default-operation-injector
   :operation-decorator            default-operation-decorator
   :path-params-injector           default-path-params-injector
   :query-params-injector          default-query-params-injector
   :body-params-injector           default-body-params-injector
   :responses-injector             default-responses-injector
   :response-decorator             default-response-decorator})

(defn construct-swagger-object
  "Constructs the root Swagger object from the Rook options, swagger options, and the routing table
  (part of the result from [[construct-namespace-handler]])."
  [swagger-options routing-table]
  (t/track
    "Constructing Swagger API Description."
    (let [{:keys [template route-injector configurer routing-entry-remove-predicate]} swagger-options
          routing-entries (->> routing-table
                               (map routing-entry->map)
                               ;; Endpoints with the :no-swagger meta data are ignored.
                               (remove #(-> % :meta :no-swagger))
                               ;; Each :all is expanded to every supported method ...
                               (mapcat expand-method-all)
                               ;; So this becomes pretty important.
                               (remove routing-entry-remove-predicate))]
      (as-> (reduce (partial route-injector swagger-options) template routing-entries) %
            (configurer swagger-options % routing-entries)))))
