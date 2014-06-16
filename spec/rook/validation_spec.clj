(ns rook.validation-spec
  (:import (javax.servlet.http HttpServletResponse)
           (java.util Date TimeZone Calendar UUID)
           (java.text SimpleDateFormat))
  (:use
    speclj.core
    ring.mock.request
    io.aviso.rook
    io.aviso.rook.schema-validation)
  (:require
    [clojure.core.async :refer [<!!]]
    [schema.core :as s]
    [clojure.tools.logging :as l]
    [io.aviso.rook.async :as async]))

(def example-schema {:name                     s/Str
                     (s/optional-key :address) [s/Str]
                     (s/optional-key :city)    s/Str})

(defn should-be-valid [expected-request [failure actual-request]]
  (should-be-nil failure)
  (should= expected-request actual-request))

(defn current-instant
  []
  (Date.))

(describe "io.aviso.rook.schema-validation"

  (describe "validate-against-schema"

    (it "returns new-request if validation is successful"
        (let [request {:params {:name "Rook"}}]
          (should-be-valid request (validate-against-schema request example-schema))))

    (it "should accept optional keys"
        (let [request {:params {:name "Rook" :address ["Division St."]}}]
          (should-be-valid request (validate-against-schema request example-schema))))

    (it "returns a failure response if validation fails"
        (let [[failures new-request] (validate-against-schema {:params {:user-name "Rook"}} example-schema)
              response (wrap-invalid-response failures)]
          (should-be-nil new-request)
          (should= HttpServletResponse/SC_BAD_REQUEST (:status response))
          (should= "validation-error" (-> response :body :error))))

    (describe "cooercions"

      (it "should cooerce strings to s/Int"
          (should-be-valid {:params {:number (int 5)}}
                           (validate-against-schema {:params {:number "5"}}
                                                    {:number s/Int})))

      (it "should cooerce strings to s/Bool"
          (should-be-valid {:params {:t true :f false}}
                           (validate-against-schema {:params {:t "true" :f "false"}}
                                                    {:t s/Bool
                                                     :f s/Bool})))

      (it "should cooerce strings to keywords"
          (should-be-valid {:params {:languages [:english :french]}}
                           (validate-against-schema {:params {:languages ["english" "french"]}}
                                                    {:languages [(s/enum :english :french)]})))

      (it "should cooerce strings to s/Inst"
          (let [now (current-instant)]
            (should-be-valid {:params {:date now}}
                             (validate-against-schema {:params {:date (format-instant now)}}
                                                      {:date s/Inst}))))

      (it "should cooerce strings to s/Uuid"
          (let [uuid (UUID/randomUUID)]

            (should-be-valid {:params {:id uuid}}
                             (validate-against-schema {:params {:id (str uuid)}}
                                                      {:id s/Uuid}))))))

  (describe "middleware"

    #_
    (it "is present in the default synchronous pipeline"
        (let [handler (namespace-handler 'validating)]
          (should= HttpServletResponse/SC_BAD_REQUEST
                   (->
                     (request :post "/")
                     (assoc :params {:first-name "Wrong Key"})
                     handler
                     :status))))

    (it "validates schemas when present"
        (let [handler (namespace-handler ['validating wrap-with-schema-validation])]
          (should= HttpServletResponse/SC_BAD_REQUEST
                   (->
                     (request :post "/")
                     (assoc :params {:first-name "Wrong Key"})
                     handler
                     :status)))))

  (describe "async middleware"

    (let [handler (namespace-handler {:async? true} ['validating]) #_(async/namespace-handler 'validating)]
      (it "is present in the default async pipeline"
          (should= HttpServletResponse/SC_BAD_REQUEST
                   (->
                     (request :post "/")
                     (assoc :params {:first-name "Wrong Key"})
                     handler
                     <!!
                     :status)))

      (it "does not interfere with valid request"

          (should= HttpServletResponse/SC_OK
                   (->
                     (request :post "/")
                     (assoc :params {:name "Provided"})
                     handler
                     <!!
                     :status))))))



(run-specs)
