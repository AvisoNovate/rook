(ns rook.schema-spec
  (:use speclj.core
        io.aviso.rook.schema)
  (:require [schema.core :as s])
  (:import [io.aviso.rook.schema IsInstance]))


(describe "io.aviso.rook.schema"

  (context "with-data-type"

    (it "promotes Class to IsInstance"
        (let [schema (with-data-type {:type :integer} s/Str)]
          (should= {:type :integer}
                   (-> schema meta :swagger-data-type))
          (should= IsInstance
                   (type schema))))))

(run-specs)
