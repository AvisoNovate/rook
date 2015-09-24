(ns rook.validation-spec
  (:use speclj.core
        ring.mock.request
        io.aviso.rook
        io.aviso.rook.schema-validation)
  (:require [schema.core :as s]
            [io.aviso.rook.schema :as rs]
            [validating])
  (:import [javax.servlet.http HttpServletResponse]
           [java.util Date UUID]))

(def example-schema {:name                     s/Str
                     (s/optional-key :address) [s/Str]
                     (s/optional-key :city)    s/Str})

(defn should-be-valid [expected-request [failure actual-request]]
  (should-be-nil failure)
  (should= expected-request actual-request))

(defn current-instant
  []
  (Date.))

(defn validate-against-schema
  ([request schema]
   (coerce-and-validate request :params (schema->coercer schema) identity))
  ([request schema coercions]
   (coerce-and-validate request :params (schema->coercer schema coercions) identity)))

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
              response (wrap-invalid-response "my/endpoint" failures)]
          (should-be-nil new-request)
          (should= HttpServletResponse/SC_BAD_REQUEST (:status response))
          (should= {:error   "invalid-request-data"
                    ;; The order in which these keys appear is very sensitive to the version of Clojure. It is defintely
                    ;; different between Clojure 1.6 and 1.7.
                    :message "Request for endpoint `my/endpoint' contained invalid data: {:user-name disallowed-key, :name missing-required-key}"}
                   (-> response :body))))

    (describe "coercions"

      (it "should coerce strings to s/Int"
          (should-be-valid {:params {:number (int 5)}}
                           (validate-against-schema {:params {:number "5"}}
                                                    {:number s/Int})))

      (it "should coerce strings to s/Bool"
          (should-be-valid {:params {:t true :f false}}
                           (validate-against-schema {:params {:t "true" :f "false"}}
                                                    {:t s/Bool
                                                     :f s/Bool})))

      (it "should coerce strings to keywords"
          (should-be-valid {:params {:languages [:english :french]}}
                           (validate-against-schema {:params {:languages ["english" "french"]}}
                                                    {:languages [(s/enum :english :french)]})))

      (it "should coerce strings to s/Inst"
          (let [now (current-instant)]
            (should-be-valid {:params {:date now}}
                             (validate-against-schema {:params {:date (format-instant now)}}
                                                      {:date s/Inst}))))

      (it "should coerce strings to s/Uuid"
          (let [uuid (UUID/randomUUID)]
            (should-be-valid {:params {:id uuid}}
                             (validate-against-schema {:params {:id (str uuid)}}
                                                      {:id s/Uuid}))))

      (it "should handle coercions that include descriptions"
          (let [uuid (UUID/randomUUID)]
            (should-be-valid {:params {:id uuid}}
                             (validate-against-schema {:params {:id (str uuid)}}
                                                      {:id (rs/with-description "A UUID" s/Uuid)}))))

      (it "should coerce with custom coercions"
          (should-be-valid {:params {:tags [:a]}}
                           (validate-against-schema {:params {:tags ["a"]}}
                                                    {:tags [s/Keyword]}
                                                    {[s/Keyword] validating/->vector})))

      (it "should coerce with custom coercions"
          (should-be-valid {:params {:tags [:a]}}
                           (validate-against-schema {:params {:tags {"0" "a"}}}
                                                    {:tags [s/Keyword]}
                                                    {[s/Keyword] validating/->vector})))))

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
                     :status))))

    (it "uses custom coercions when present"
        (let [handler  (namespace-handler ['validating wrap-with-schema-validation])
              resp-vec (-> (request :get "/")
                           (assoc :params {:tags ["big"]})
                           handler)
              resp-map (-> (request :get "/")
                           (assoc :params {:tags {"0" "big"}})
                           handler)]
          (should= HttpServletResponse/SC_OK (:status resp-vec))
          (should= ["big"] (:body resp-vec))
          (should= HttpServletResponse/SC_OK (:status resp-map))
          (should= ["big"] (:body resp-map))))))

(run-specs)
