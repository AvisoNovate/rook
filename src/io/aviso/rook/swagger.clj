(ns io.aviso.rook.swagger
  "Generates a Swagger 2.0 API description from Rook namespace metadata.

  This is currently under heavy development and is likely to be somewhat unstable for a couple of releases.


  Building the Swagger document is complex, and it is not a perfect mapping from Rook and Prismatic Schema to
  Swagger and Swagger's customized version of JSON Schema.

  The process works from the routing table, an expanded list of all the Rook endpoint namespaces, generated as part of the work of
  [[construct-namespace-handler]].

  This data is pulled apart and used to build up a [[SwaggerObject]] through multiple iterations.

  The SwaggerOption includes two types of callbacks: injectors and decorators.

  Injectors do the main work of building any individual piece of the SwaggerObject, such as an operation,
  or a possible response from an operation.
  Injectors can be overridden, but this is often not necessary, and should be avoided where possible.

  Decorators receive the output of the injector and can modify the result which is then placed into the appropriate
  place within the SwaggerObject.
  The default decorators ignore most of their arguments, and return the data to be decorator unchanged.

  It is common to add decorators that understand endpoint metadata, and modify or add information to
  the Swagger description that is application specific: the canonical example is documenting authentication
  in terms of permissions and/or roles required."
  (:require [schema.core :as s]
            [io.aviso.rook.schema :refer [unwrap-schema SchemaUnwrapper]]
            [clojure.string :as str]
            [medley.core :as medley]
            [io.aviso.toolchest.macros :refer [cond-let]]
            [io.aviso.toolchest.collections :refer [pretty-print]]
            [io.aviso.tracker :as t]
            [io.aviso.rook.dispatcher :as dispatcher])
  (:import [schema.core EnumSchema Maybe Both Predicate AnythingSchema]
           [io.aviso.rook.schema IsInstance]
           [clojure.lang IPersistentMap IPersistentVector]))




(s/defschema ^{:added "0.1.36"} SwaggerSchema
  "Swagger Schemas are, again, quite variable, with involved rules about what is optional and required."
  {(s/optional-key "$ref")       s/Str
   (s/optional-key :description) s/Str
   s/Any                         s/Any})

(s/defschema ^{:added "0.1.36"} Response
  {:description             s/Str
   (s/optional-key :schema) SwaggerSchema
   s/Any                    s/Any})

(s/defschema ^{:added "0.1.36"} Responses
  "A set of responses, based on numeric HTTP status code.

  The numeric keys will be converted to strings when the entire structure is exported as JSON."
  {s/Num Response})

(s/defschema ^{:added "0.1.36"} ParameterLocation
  (s/enum :query :header :path :formData :body))

(s/defschema ^{:added "0.1.36"} Parameter
  {:name                      s/Str
   :in                        ParameterLocation
   (s/optional-key :required) s/Bool
   (s/optional-key :schema)   SwaggerSchema
   ;; There's a lot more, since it's possible to specify the type inlne, or an array of a type
   ;; (schema, schema reference, or inline type). It's hard to model the rules of what is allowed
   ;; or required, but this is a good start.
   s/Any                      s/Any})

(s/defschema ^{:added "0.1.36"} Operation
  {(s/optional-key :description) s/Str
   ;; Rook always adds this, but it is optional in the Swagger 2.0 spec.
   (s/optional-key :operationId) s/Str
   (s/optional-key :parameters)  [Parameter]
   :responses                    Responses
   s/Any                         s/Any})

(s/defschema ^{:added "0.1.36"} PathItem
  {(s/optional-key s/Keyword) Operation
   s/Any                      s/Any})

(s/defschema ^{:added "0.1.36"} Paths
  ;; s/Str would seem to make sense here, but results in ClassCastExceptions
  {s/Str PathItem
   ;; Running into Prismatic Schema limitations here, attempt to add
   ;; s/Any s/Any results in "More than one non-optional/required key schemata: [java.lang.String Any]"
   })

(s/defschema ^{:added "0.1.36"} Definitions
  "The definitions part of the Swagger Object; string keys (identified via the \"$ref\" key in other parts
  of the SwaggerObject) identify entries here. It is kept wide open due to the internal complexity of schemas."
  {s/Str s/Any})

(s/defschema ^{:added "0.1.36"} SwaggerObject
  "Defines the *required* properties of the SwaggerObject. "
  {:swagger     s/Str
   :info        {:title   s/Str
                 :version s/Str
                 s/Any    s/Any}
   :paths       Paths
   :definitions Definitions
   ;; And quite a few more. The above are just the required ones.
   s/Any        s/Any})

(s/defschema ^{:added "0.1.36"} PathTerm
  "A term in a path is either a literal string, or a keyword (representing a path variable)."
  (s/either s/Str s/Keyword))

(s/defschema ^{:added "0.1.36"} Method
  "An HTTP method (:get, :post, etc.) used for routing and matching of endpoints."
  (apply s/enum :all dispatcher/supported-methods))

(s/defschema ^{:added "0.1.36"} Route
  "Describes a route mapping to one (or more) endpoints, interms of a method and a path."
  [(s/one Method 'method) (s/one [PathTerm] 'path)])

(s/defschema ^{:added "0.1.36"} Metadata
  "Endpoint metadata, which includes all metadata inherited from the namespace, and some specific keys added by
  Rook."
  {:function  s/Str                                         ; fully qualified name of the endpoint function
   :route     Route
   :arguments [s/Symbol]
   s/Any      s/Any})

(s/defschema ^{:added "0.1.36"} RoutingEntry
  "The routing data about a single endpoint function."
  {:method Method
   :path   [PathTerm]
   :meta   Metadata})

(s/defschema ^{:added "0.1.36"} JSONType
  "The type of a parameter."
  {:type                    (s/enum :integer :number :string :boolean)
   ;; Although there's a few common types, this is actually wide-open.
   (s/optional-key :format) s/Keyword})

(s/defschema ^{:added "0.1.36"} ParamsKey
  "Path from the root of the [[SwaggerObject]] to the parameters object of a specific operation."
  [s/Any])

(def ^:private base-swagger-options
  {:template                       SwaggerObject
   ;; Path at which the swagger.json resource will be exposed; useful for moving it down from the root
   ;; e.g. ["api"] to make the URI "/api/swagger.json".
   :path                           [s/Str]
   ;; Name of the request parameter used for the body of the request.
   :body-name                      s/Keyword
   ;; Mapping of header name to a short description of the meaning of the header.
   :default-header-descriptions    {s/Str s/Str}
   ;; This predicate is used to remove some RoutingEntries, good for internal or testing
   ;; endpoints that should not be documented.
   :routing-entry-remove-predicate (s/=> s/Bool RoutingEntry)
   :data-type-mappings             {s/Any JSONType}})

(s/defschema ^{:added "0.1.36"} SwaggerOptions
  "The options which are passed to [[construct-swagger-object]], and then passed to most of the injectors
  and decorators. This doesn't include the callbacks, as those are themselves defined in terms of SwaggerOptions,
  and Schema can't do such recursive definitions."
  (assoc base-swagger-options
    ;; This is a placeholder for the callback types defined in FullSwaggerOptions
    s/Any s/Any))

(s/defschema ^{:added "0.1.36"} ParamsInjector
  "A function that adds parameter defintiions of a particular type to an [[Operation]]."
  (s/=> SwaggerObject SwaggerOptions SwaggerObject RoutingEntry ParamsKey))

(s/defschema ^{:added "0.1.36"} FullSwaggerOptions
  "The options which are passed to [[construct-swagger-object]], and then passed to most of the injectors
  and decorators.  This is the same as [[SwaggerOptions]], but with the various injector and decorator
  callbacks included."
  (merge base-swagger-options
         {:route-injector         (s/=> SwaggerObject SwaggerOptions SwaggerObject RoutingEntry)
          :configurer             (s/=> SwaggerObject SwaggerOptions SwaggerObject [RoutingEntry])
          :operation-injector     (s/=> SwaggerObject SwaggerOptions SwaggerObject RoutingEntry [s/Any])
          :operation-decorator    (s/=> PathItem SwaggerOptions SwaggerObject RoutingEntry PathItem)
          :path-params-injector   ParamsInjector
          :query-params-injector  ParamsInjector
          :body-params-injector   ParamsInjector
          :header-params-injector ParamsInjector
          :responses-injector     (s/=> SwaggerObject SwaggerOptions SwaggerObject RoutingEntry [s/Any])
          :response-decorator     (s/=> Response SwaggerOptions SwaggerObject RoutingEntry s/Num s/Any Response)}))


(defn- remove-nil-vals
  [m]
  (medley/remove-vals nil? m))

(defn- is-nullable?
  [schema]
  (instance? Maybe schema))

(defn- count-indentation
  "For an input string, "
  [input]
  (let [[_ ^String indent] (re-matches #"(\s*).*" input)]
    [(.length indent) input]))

(defn- cleanup-indentation-for-markdown
  "Fixes the indentation of a :doc or :description string to be valid for (extended) Markdown."
  [input]
  (cond-let
    (nil? input)
    nil

    [lines (str/split-lines input)]

    (= 1 (count lines))
    input

    [lines' (mapv count-indentation lines)
     non-zero-indents (->> lines'
                           (map first)
                           (filter pos?))]

    (empty? non-zero-indents)
    input

    [min-indent (reduce min non-zero-indents)]

    (zero? min-indent)
    input

    :else
    (apply str
           (interpose "\n"
                      (for [[indent line] lines']
                        (if (zero? indent)
                          line
                          (subs line min-indent)))))))

(defn- unwrap
  [schema]
  (if (satisfies? SchemaUnwrapper schema)
    (unwrap-schema schema)))

(defn- direct-doc
  [metadata]
  (or (:usage-description metadata)
      (:description metadata)
      (:doc metadata)))

(defn- find-documentation
  [holder]
  (cond-let
    (nil? holder)
    nil

    [holder-meta (meta holder)]

    [docs (direct-doc holder-meta)]

    (some? docs)
    docs

    [unwrapped (unwrap holder)]

    (some? unwrapped)
    (recur unwrapped)

    :else
    nil))

(defn- extract-documentation
  [holder]
  (cleanup-indentation-for-markdown (find-documentation holder)))

(defn- merge-in-description
  [swagger-schema schema]
  (if-let [description (extract-documentation schema)]
    (assoc swagger-schema :description description)
    swagger-schema))

(s/def default-swagger-template :- SwaggerObject
  "A base skeleton for a Swagger Object (as per the Swagger 2.0 specification), that is further populated from Rook namespace
  and schema data."
  {:swagger     "2.0"
   :paths       (sorted-map)
   :definitions (sorted-map)
   :schemes     ["http" "https"]
   :consumes    ["application/json" "application/edn"]
   :produces    ["application/json" "application/edn"]
   :info        {:title   "<UNSPECIFIED>"
                 :version "<UNSPECIFIED>"}})

(defn- keyword->path?
  [v]
  (if (keyword? v)
    (str \{ (name v) \})
    v))

(s/defn path->str :- s/Str
  "Converts a Rook path (sequence of strings and keywords) into a Swagger path; e.g. [\"users\" :id] to \"/users/{id}\"."
  [path :- [PathTerm]]
  (->>
    path
    (map keyword->path?)
    (str/join "/")
    (str "/")))

(s/defn parameter-object :- Parameter
  "Creates a parameter object, as part of an operation."
  {:added "0.1.29"}
  [parameter-name :- (s/either s/Str s/Symbol s/Keyword)
   location :- ParameterLocation
   required? :- s/Bool
   description :- (s/maybe s/Str)]
  (cond-> {:name (name parameter-name)
           :type :string                                    ; may add something later to refine this
           :in   location}
          required? (assoc :required required?)
          description (assoc :description description)))

(s/defn default-path-params-injector :- SwaggerObject
  "Identifies each path parameter and adds a value for it to the swagger-object at the path defined by params-key."
  [swagger-options :- SwaggerOptions
   swagger-object :- SwaggerObject
   routing-entry :- RoutingEntry
   params-key :- ParamsKey
   ]
  (let [path-ids (->> routing-entry :path (filter keyword?))
        id->docs (->> routing-entry
                      :meta
                      :arguments
                      (map (fn [sym] [(keyword (name sym))
                                      ;; Possibly we could pick up a default from swagger-options?
                                      (-> sym meta :description)]))
                      (into {}))
        reducer  (fn [so path-id]
                   (let [description (get id->docs path-id)
                         param-object (parameter-object path-id :path true description)]
                     (update-in so params-key conj param-object)))]
    (reduce reducer swagger-object path-ids)))

(s/defn default-header-params-injector :- SwaggerObject
  "Identifies each header parameter and adds a value for it to the swagger-object at the path defined by params-key.
   Headers are recognized via the :rook-header-name metadata on the argument resolver for the argument."
  {:since "0.1.29"}
  [swagger-options :- SwaggerOptions
   swagger-object :- SwaggerObject
   routing-entry :- RoutingEntry
   params-key :- ParamsKey]
  (let [resolvers (-> routing-entry :meta :argument-resolvers)
        reducer
                  (fn [so arg-sym]
                    (let [resolver    (get resolvers arg-sym)
                          header-name (-> resolver meta :header-name)]
                      (if header-name
                        (let [description (or
                                            (-> arg-sym meta :description)
                                            (get (:default-header-descriptions swagger-options) header-name))]
                          (update-in so params-key conj (parameter-object header-name :header false description)))
                        so)))]
    (reduce reducer swagger-object (-> routing-entry :meta :arguments))))


(defn- analyze-schema-key
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
    Return a tuple of the (possibly updated) swagger-object and the converted schema."))

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
    ;; Generally, the description (if any) is on the IsInstance, and what's wrapped is typically
    ;; a java.lang.Class with no metadata.
    (simple->swagger-schema swagger-options swagger-object (unwrap-schema schema) schema))

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

(defn- find-data-type-mapping
  [swagger-options schema]
  (cond-let
    (nil? schema)
    nil

    [data-type-meta (-> schema meta :swagger-data-type)]

    (some? data-type-meta)
    data-type-meta

    [data-type (-> swagger-options :data-type-mappings (get schema))]

    (some? data-type)
    data-type

    :else
    (recur swagger-options (unwrap schema))))

(defn- simple->swagger-schema
  "Converts a simple (non-object) Schema into an inline Swagger Schema, with keys :type and perhaps :format, :description, etc."
  ([swagger-options swagger-object schema]
   (simple->swagger-schema swagger-options swagger-object schema schema))
  ([swagger-options swagger-object schema description-schema]
   (cond-let

     [data-type (find-data-type-mapping swagger-options schema)]

     (some? data-type)
     [swagger-object (merge-in-description data-type description-schema)]

     :else
     (convert-schema schema swagger-options swagger-object))))

(s/defn default-query-params-injector :- SwaggerObject
  "Identifies each query parameter via the :query-schema metadata and injects it."
  [swagger-options :- SwaggerOptions
   swagger-object :- SwaggerObject
   routing-entry :- RoutingEntry
   params-key :- ParamsKey]
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
                              full-description (cond-> (-> swagger-schema
                                                           (assoc :name k
                                                                  :in :query
                                                                  :required required)
                                                           (merge-in-description key-schema))
                                                       (is-nullable? key-schema) (assoc :allowEmptyValue true))]
                          (update-in swagger-object' params-key
                                     conj full-description)))))]
      (reduce reducer swagger-object schema))
    ; no :query-schema
    swagger-object))

(s/defn map->swagger-schema :- [(s/one SwaggerObject 'swagger-object)
                                (s/one SwaggerSchema 'swagger-schema)]
  [swagger-options :- SwaggerOptions
   swagger-object :- SwaggerObject
   schema :- s/Any]
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
                               nullable? (is-nullable? schema-value)
                               swagger-schema' (cond-> (merge-in-description swagger-schema schema-value)
                                                       nullable? (assoc :x-nullable true))
                               path (if (= ::wildcard k)
                                      [:additionalProperties]
                                      [:properties k])]
                           [acc-so' (cond-> (assoc-in acc-schema path swagger-schema')
                                            required? (update-in [:required] conj k))])))))]
    (let [base (remove-nil-vals {:type        :object
                                 :description (extract-documentation schema)})]
      (reduce reducer [swagger-object base] schema))))

(defn- strip-usage-doc
  [schema]
  (vary-meta schema dissoc :usage-description))

(defn- merge-direct-doc
  "Merges in documentation that's directly on the provided schema (but ignores nested)."
  [swagger-schema schema]
  (let [local-doc (-> schema meta direct-doc)]
    (if local-doc
      (assoc swagger-schema :description local-doc)
      swagger-schema)))

;; Can we merge simple->swagger-schema and ->swagger-schema?

(defn- ->swagger-schema
  "Converts a Prismatic schema to a Swagger schema (or possibly, a \"$ref\" map pointing to a schema). A $ref
  will occur when the schema has the :name and :ns metadata, otherwise it will be inlined.

  nil or s/Any returns nil.

  Typically, the result of this is wrapped inside map with key :schema."
  [swagger-options swagger-object schema]
  (cond-let
    (or (nil? schema) (= s/Any schema))
    [swagger-object nil]

    (vector? schema)
    ;; We should probably verify that a vector has a single schema element; that's all that
    ;; JSON schemas can handle, which is a subset of Schema.
    (let [[so' item-reference] (->swagger-schema swagger-options swagger-object (first schema))]
      [so' (merge-direct-doc {:type  :array
                              :items item-reference}
                             schema)])

    [schema-name (s/schema-name schema)
     schema-ns (-> schema meta :ns)]

    ;; Missing this stuff?  Make it anonymous.
    (or (nil? schema-name)
        (nil? schema-ns))
    ;; Nested schemas may be named, however, and will need to write into the swagger-object :definitions
    ;; map.
    ;; For these anonymous/inline schemas, we strip out the doc & description, as that should have been captured at the
    ;; point where the schema is first referenced (i.e., a response definition).
    (let [[so' new-schema] (map->swagger-schema swagger-options swagger-object (strip-usage-doc schema))]
      [so' new-schema])

    ;; Avoid forward slash in the swagger name, as that's problematic, especially for
    ;; the swagger-ui (which must be taking a few liberties or shortcuts).
    [swagger-name (str schema-ns \: schema-name)
     usage-doc (-> schema meta :usage-description)
     swagger-reference (cond-> {"$ref" (str "#/definitions/" swagger-name)}
                               usage-doc (assoc :description usage-doc))
     swagger-schema (get-in swagger-object [:definitions swagger-name])]

    (some? swagger-schema)
    [swagger-object swagger-reference]

    [[so-1 new-schema] (map->swagger-schema swagger-options swagger-object (strip-usage-doc schema))
     so-2 (assoc-in so-1 [:definitions swagger-name] new-schema)]

    :else
    [so-2 swagger-reference]))

(s/defn default-body-params-injector :- SwaggerObject
  "Uses the :body-schema metadata to identify the body params."
  [swagger-options :- SwaggerOptions
   swagger-object :- SwaggerObject
   routing-entry :- RoutingEntry
   params-key :- ParamsKey]
  (t/track
    "Describing body parameters."
    (if-let [schema (-> routing-entry :meta :body-schema)]

      ;; Assumption: it's a map and named
      (let [[swagger-object' schema-reference] (->swagger-schema swagger-options swagger-object schema)]
        (update-in swagger-object' params-key
                   conj {:name     (:body-name swagger-options)
                         :in       :body
                         :required true
                         :schema   schema-reference}))
      ; no schema:
      swagger-object)))

(s/defn default-responses-injector :- SwaggerObject
  "Uses the :responses metadata to identify possible responses."
  [swagger-options :- SwaggerOptions
   swagger-object :- SwaggerObject
   routing-entry :- RoutingEntry
   paths-key :- [s/Any]]
  (let [responses (-> routing-entry :meta :responses)
        decorator (:response-decorator swagger-options)
        reducer   (fn [so [status-code schema]]
                    (t/track
                      #(format "Describing %d response." status-code)
                      (let [description (or (extract-documentation schema)
                                            ;; description is required in the Response Object, so we need some default here.
                                            "Documentation not provided.")
                            [so' schema-reference] (->swagger-schema swagger-options so schema)
                            response    (->> {:description description
                                              :schema      schema-reference}
                                             remove-nil-vals
                                             (decorator swagger-options so routing-entry status-code schema))]
                        (assoc-in so' (concat paths-key [:responses status-code]) response))))]
    (reduce reducer swagger-object responses)))

(s/defn default-operation-decorator :- PathItem
  "Decorates a PathItemObject (which describes a single Rook endpoint) just before it is added to the Swagger object.
  This implementation returns it unchanged."
  [swagger-options :- SwaggerOptions
   swagger-object :- SwaggerObject
   routing-entry :- RoutingEntry
   path-item-object :- PathItem]
  path-item-object)

(s/defn default-operation-injector :- SwaggerObject
  "Injects an Object object based on a single routing entry.

  The paths-key is a coordinate into the swagger-object where the PathItemObject should be created.

  Returns the modified swagger-object."
  [swagger-options :- SwaggerOptions
   swagger-object :- SwaggerObject
   routing-entry :- RoutingEntry
   paths-key :- [s/Any]]
  {:pre [(vector? paths-key)]}
  (let [{endpoint-meta :meta} routing-entry
        description  (-> (direct-doc endpoint-meta)
                         cleanup-indentation-for-markdown)
        summary      (:summary endpoint-meta)
        {:keys [path-params-injector query-params-injector body-params-injector header-params-injector
                responses-injector operation-decorator]} swagger-options
        params-key   (conj paths-key :parameters)]
    (as-> swagger-object %
          (assoc-in % paths-key
                    (remove-nil-vals {:description description
                                      :summary     summary
                                      ;; This is required inside a Operation object:
                                      :responses   (sorted-map)
                                      ;; There are a couple of scenarios where the function name is not
                                      ;; unique, alas. But that doesn't seem to cause problems.
                                      :operationId (:function endpoint-meta)}))
          (path-params-injector swagger-options % routing-entry params-key)
          (query-params-injector swagger-options % routing-entry params-key)
          (body-params-injector swagger-options % routing-entry params-key)
          (header-params-injector swagger-options % routing-entry params-key)
          (responses-injector swagger-options % routing-entry paths-key)
          (update-in % paths-key (fn [operation]
                                   (operation-decorator swagger-options % routing-entry operation))))))

(s/defn default-route-injector :- SwaggerObject
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
  [swagger-options :- SwaggerOptions
   swagger-object :- SwaggerObject
   routing-entry :- RoutingEntry]
  (t/track
    #(format "Describing endpoint %s `/%s'."
             (-> routing-entry :method name .toUpperCase)
             (->> routing-entry :path (str/join "/")))
    (let [path-str           (-> routing-entry :path path->str)
          ;; Ignoring :all for the moment.
          method             (-> routing-entry :method)
          operation-injector (:operation-injector swagger-options)]
      ;; Invoke the constructor for the path info. It may need to make changes to the :definitions, so we have
      ;; to let it modify the entire Swagger object ... but we help it out by providing the path
      ;; to where the PathInfoObject (which describes what Rook calls a "route").
      (operation-injector swagger-options swagger-object routing-entry
                          [:paths path-str method]))))

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

(s/defn default-configurer :- SwaggerObject
  "The configurer is passed the swagger options, the final Swagger object, and the seq of routing entries,
   and can make final changes to it. This implementation does nothing, returning the Swagger object unchanged."
  [swagger-options :- SwaggerOptions
   swagger-object :- SwaggerObject
   routing-entries :- [RoutingEntry]]
  swagger-object)

(s/defn default-response-decorator :- Response
  "A callback to decorate a specific response object just before it is stored into the swagger-object."
  [swagger-options :- SwaggerOptions
   swagger-object :- SwaggerObject
   routing-entry :- RoutingEntry
   status-code :- s/Num
   response-schema :- s/Any
   response-object :- Response]
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

(s/def default-swagger-options :- FullSwaggerOptions
  {:template                       default-swagger-template
   :path                           []
   ;; Name of special parameter used for the body of the request
   :body-name                      :body
   :default-header-descriptions    {"if-match"            "Used for optimistic locking checks in resource updates."
                                    "if-none-match"       "Used for optimistic locking checks in resource updates."
                                    "if-modified-since"   "Used for cache checking when reading a resource."
                                    "if-unmodified-since" "Used for cache checking when reading a resource."}
   :routing-entry-remove-predicate (constantly false)
   :route-injector                 default-route-injector
   :configurer                     default-configurer
   :data-type-mappings             default-data-type-mappings
   :operation-injector             default-operation-injector
   :operation-decorator            default-operation-decorator
   :path-params-injector           default-path-params-injector
   :query-params-injector          default-query-params-injector
   :body-params-injector           default-body-params-injector
   :header-params-injector         default-header-params-injector
   :responses-injector             default-responses-injector
   :response-decorator             default-response-decorator})

(s/defn construct-swagger-object :- SwaggerObject
  "Constructs the root Swagger object from the Rook options, swagger options, and the routing table
  (part of the result from [[construct-namespace-handler]])."
  [swagger-options :- FullSwaggerOptions
   routing-table]
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
