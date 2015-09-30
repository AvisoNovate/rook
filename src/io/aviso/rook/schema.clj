(ns io.aviso.rook.schema
  "Some small enhancements to Prismatic Schema that are valuable to, or needed by, the Swagger support."
  {:added "0.1.27"}
  (:require [schema.core :as s]
            [schema.spec.leaf :as leaf]
            [io.aviso.toolchest.macros :refer [cond-let]]
            [io.aviso.toolchest.metadata :refer [assoc-meta]])
  (:import [schema.core Maybe EnumSchema Both OptionalKey Either]))

(defmacro schema
  "Creates a named schema, which includes metadata as per [[defschema]]. This is useful for one-off
  schemas, such as those used in responses."
  ([name form]
   `(schema ~name "" ~form))
  ([name docstring form]
   `(-> ~form
        (vary-meta merge ~(-> &form second meta))
        ;; The second form is the name (the first is the 'schema symbol itself) and we want its metadata
        (assoc-meta :name '~name :ns *ns* :doc ~docstring))))

(defmacro defschema
  "Convenience macro to make it clear to reader that body is meant to be used as a schema, and to provide
  extra data needed for generating Swagger descriptions.

  This extends schema.core/defschema to merge the symbol's metadata with keys :name (the unqualified
  symbol being defined), :ns (the namespace), :doc (the docstring) plus any custom metdata
   from the name.  Normally, much of that data is only available to the Var created by the underlying def."
  ([name form]
   `(defschema ~name "" ~form))
  ([name docstring form]
   `(def ~name ~docstring (schema ~name ~docstring ~form))))

(defprotocol SchemaUnwrapper
  "A protocol for 'unwrapping' a Schema, to extract a nested Schema (or, in
  certain cases a seq of schemas)."

  (unwrap-schema [this]
    "Returns the nested schema (or schemas) where appropriate, or throws an
    exception when a nested schema is not available.")

  (unwrap-cardinality [this]
    "Returns :one or :many depending on whether the given schema wraps a
    single child schema or a number of children."))

(defrecord IsInstance [^Class expected-class]
  s/Schema

  (spec [_]
    (leaf/leaf-spec
      (fn [x]
        (when (instance? expected-class x)
          x))))

  (explain [_]
    (list 'instance? expected-class))

  SchemaUnwrapper

  (unwrap-schema [_] expected-class)

  (unwrap-cardinality [_] :one))

(extend-protocol SchemaUnwrapper

  OptionalKey
  (unwrap-schema [this]
    (s/explicit-schema-key this))

  (unwrap-cardinality [this]
    :one)

  Maybe
  (unwrap-schema [this]
    (.schema this))

  (unwrap-cardinality [this]
    :one)

  EnumSchema
  (unwrap-schema [this]
    (.vs this))

  (unwrap-cardinality [this]
    :many)

  Both
  (unwrap-schema [this]
    (.schemas this))

  (unwrap-cardinality [this]
    :many)

  Either
  (unwrap-schema [this]
    (.schemas this))

  (unwrap-cardinality [this]
    :many))

(defn- assoc-schema-meta
  [schema key value]
  (cond
    (nil? schema)
    (recur s/Any key value)

    (= Class (type schema))
    (recur (IsInstance. schema) key value)

    :else
    (assoc-meta schema key value)))

(defn with-description
  "Adds a :description key to the metadata of the schema.

  Since nil can't have metadata, a nil schema is quietly converted to schema.core/Any. Likewise,
  a class is wrapped using an [[IsInstance]] schema."
  [description schema]
  (assoc-schema-meta schema :description description))

(defn with-usage-description
  "Adds :usage-description key to the metadata.

  The usage description is used at the point a schema is referenced.
  In output, this will be description of the property that *uses* the schema.
  Later in the output documentation, the schema itself will be output, using just its :description
  (or :doc) metadata."
  {:added "0.1.33"}
  [usage-description schema]
  (assoc-schema-meta schema :usage-description usage-description))

(defn description
  "A convienience for generating a description with no schema."
  [s]
  (with-description s nil))

(s/defn with-data-type
  "Adds Swagger-specific data type metadata to a Schema.

  A Class is promoted to an [[IsInstance]] schema."
  {:added "0.1.33"}
  [swagger-meta :- {:type
                    ;; As per http://json-schema.org/latest/json-schema-core.html#anchor4
                    (s/enum :string :number :boolean :array :integer :null :object)

                    ;; Although there's a bunch of commonly accepted values for this, it's actually
                    ;; designed to be open-ended.
                    (s/optional-key :format) s/Any}
   schema]
  (assoc-schema-meta schema :swagger-data-type swagger-meta))


(defn coercion-matcher
  "A coercion matcher that builds on a delegate matcher (schema.coerce/string-coercion-matcher, typically), but understands how to unwwrap
  schemas to eventually get to the class to coercer mapping."
  {:added "0.1.31"}
  [schema delegate-coercion-matcher]
  (cond-let
    (nil? schema)
    nil

    [matcher (delegate-coercion-matcher schema)]

    (some? matcher)
    matcher

    (satisfies? SchemaUnwrapper schema)
    (let [card      (unwrap-cardinality schema)
          unwrapped (unwrap-schema schema)]
      (if (= :one card)
        (recur unwrapped delegate-coercion-matcher)
        (some #(coercion-matcher % delegate-coercion-matcher) unwrapped)))

    :else
    nil))

