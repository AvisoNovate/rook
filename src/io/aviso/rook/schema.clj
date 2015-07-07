(ns io.aviso.rook.schema
  "Some small enhancements to Prismatic Schema that are valuable to, or needed by, the Swagger support."
  {:added "0.1.27"}
  (:require [schema.core :as s]
            [schema.macros :as macros]
            [io.aviso.toolchest.macros :refer [cond-let]]
            [io.aviso.toolchest.metadata :refer [assoc-meta]]
            [schema.coerce :as coerce])
  (:import [schema.core Maybe EnumSchema Both OptionalKey]))

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
  "A protocol for 'unwrapping' a Schema, to extract a nested Schema (or, in certain cases a seq of schemas)."

  (unwrap-schema [this]
    "Returns the nested schema (or schemas) where appropriate, or throws an exception when a nested schema is not available."))

(defrecord IsInstance [^Class expected-class]
  s/Schema

  (walker [this]
    (fn [x]
      (if (instance? expected-class x)
        x
        (macros/validation-error this x (list 'instance? expected-class x)))))

  (explain [_]
    (list 'instance? expected-class))

  SchemaUnwrapper

  (unwrap-schema [_] expected-class))

(extend-protocol SchemaUnwrapper

  OptionalKey
  (unwrap-schema [this]
    (s/explicit-schema-key this))

  Maybe
  (unwrap-schema [this]
    (.schema this))

  EnumSchema
  (unwrap-schema [this]
    (.vs this))

  Both
  (unwrap-schema [this]
    (.schemas this)))

(defn with-description
  "Adds a :description key to the metadata of the schema.

  Since nil can't have metadata, a nil schema is quietly converted to schema.core/Any."
  [description schema]
  (cond
    (nil? schema)
    (recur description s/Any)

    (= Class (type schema))
    (recur description (IsInstance. schema))

    :else
    (assoc-meta schema :description description)))

(defn description
  "A convienience for generating a description with no schema."
  [s]
  (with-description s nil))


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
    (recur (unwrap-schema schema) delegate-coercion-matcher)

    :else
    nil))

