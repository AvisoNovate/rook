(ns rook.validation-spec
  (:import (javax.servlet.http HttpServletResponse))
  (:use
    speclj.core
    ring.mock.request
    io.aviso.rook
    io.aviso.rook.schema-validation)
  (:require
    [schema.core :as s]
    [clojure.tools.logging :as l]
    [io.aviso.rook.async :as async]))

(def example-schema {:name                     s/Str
                     (s/optional-key :address) [s/Str]
                     (s/optional-key :city)    s/Str})

(describe "io.aviso.rook.schema-validation"

  (describe "validate-against-schema"

    (it "returns nil if validation is successful"
        (should-be-nil
          (validate-against-schema {:params {:name "Rook"}} example-schema)))

    (it "should accept optional keys"
        (should-be-nil
          (validate-against-schema {:params {:name "Rook" :address ["Division St."]}} example-schema)))

    (it "returns a failure response if validation fails"
        (let [response (validate-against-schema {:params {:user-name "Rook"}} example-schema)]
          (should-not-be-nil response)
          (should= HttpServletResponse/SC_BAD_REQUEST (:status response))
          (should= "validation-error" (-> response :body :error))))

    (it "ignores extra keys"
        (should-be-nil
          (validate-against-schema {:params {:name "Rook" :ssn "999-99-9999"}} example-schema))))


  (describe "middleware"

    (it "is present in the default synchronous pipeline"
        (let [handler (namespace-handler 'validating)]
          (should= HttpServletResponse/SC_BAD_REQUEST
                   (->
                     (request :post "/")
                     (assoc :data {:first-name "Wrong Key"})
                     handler
                     :status))))))

(run-specs)
