(ns rook.swagger-spec
  (:use io.aviso.rook
        speclj.core
        clojure.repl
        clojure.template
        clojure.pprint)
  (:require [io.aviso.rook.dispatcher :as d]
            [clj-yaml.core :as yaml]
            [cheshire.core :as json]
            [io.aviso.rook.swagger :as sw]
            [io.aviso.rook :as rook]
            [clj-http.client :as client]
            [io.aviso.toolchest.collections :refer [pretty-print]]
            [qbits.jet.server :as jet]
            [io.aviso.tracker :as t]
            [clojure.tools.logging :as l]
            [io.aviso.rook.server :as server]
            [io.aviso.rook.schema :as rs]
            [schema.core :as s])
  (:import [org.eclipse.jetty.server Server]
           [javax.servlet.http HttpServletResponse]))

(defn- swagger-object
  [rook-options swagger-options & ns-specs]
  (let [[_ routing-table] (d/construct-namespace-handler rook-options ns-specs)]
    (sw/construct-swagger-object swagger-options routing-table)))

(defn get*
  [path]
  (client/get (str "http://localhost:8192" path)
              {:throw-exceptions false
               :accept           :json}))

(defmacro should-have-status
  [expected-status response]
  `(let [response#        ~response
         expected-status# ~expected-status]
     (if (= expected-status# (:status response#))
       response#
       (-fail (format "Expected status %d in response:\n%s"
                      expected-status#
                      (pretty-print response#))))))


(def swagger-options (-> sw/default-swagger-options
                         (assoc-in [:template :info :title] "Hotels and Rooms API")
                         (assoc-in [:template :info :version] "Pre-Alpha")))

(comment
  (binding [t/*log-trace-level* :info]
    (-> (swagger-object nil swagger-options
                        ["hotels" 'hotels
                         [[:hotel-id "rooms"] 'rooms]])
        (json/generate-string {:pretty true})
        println)))

(defn start-server
  []
  (let [creator        #(rook/namespace-handler {:swagger-options swagger-options}
                                                ["hotels" 'hotels
                                                 [[:hotel-id "rooms"] 'rooms]])
        handler        (server/construct-handler {:log true :track true :standard true :exceptions true} creator)
        ^Server server (jet/run-jetty {:ring-handler handler
                                       :join?        false
                                       :port         8192})]
    #(.stop server)))

(defn- join-lines [& lines]
  (apply str (interpose "\n" lines)))

(describe "io.aviso.rook.swagger"

  (with-all swagger (swagger-object nil swagger-options
                                    ["hotels" 'hotels
                                     [[:hotel-id "rooms"]
                                      'rooms]]))

  (it "can construct swagger data"
      (should-not-be-nil @swagger))

  (context "extraction of documentation"

    (it "can extract nested documentation"
        (should= "got it"
                 (sw/extract-documentation (s/maybe (rs/with-description "got it" s/Str)))))

    (it "knows when to stop"
        (should-be-nil
          (sw/extract-documentation (s/maybe s/Str)))))

  (context "extraction of data type"
    (it "can find mapping from wrapped schemas"
        (should= {:type :integer}
                 (sw/find-data-type-mapping sw/default-swagger-options (s/maybe (rs/with-description "wrapped" s/Int)))))

    (it "knows when to stop"
        (should-be-nil
          (sw/find-data-type-mapping sw/default-swagger-options StringBuffer))))

  (context "markdown"

    (it "can re-indent markdown properly"

        (should=
          (join-lines
            "foo"
            ""
            "bar"
            ": bar desc"
            ""
            "baz"
            ": baz desc"
            "")
          (sw/cleanup-indentation-for-markdown
            "foo

            bar
            : bar desc

            baz
            : baz desc
            ")))

    (it "handles edge cases"

        (should-be-nil (sw/cleanup-indentation-for-markdown nil))

        (let [input "single line"]
          (should-be-same input (sw/cleanup-indentation-for-markdown input)))

        (let [input "multiple lines\r\nwithout\nindentation"]
          (should-be-same input (sw/cleanup-indentation-for-markdown input)))

        (let [input "only a single
        line is indented"]
          (should= "only a single\nline is indented" (sw/cleanup-indentation-for-markdown input)))))

  (context "schemas"

    (rs/defschema ItemCost
      "The cost of an item."
      {(s/optional-key :currency) (rs/with-description "Three digit currency code." s/Str)
       :amount                    (rs/with-description "Numeric value." s/Str)})

    (rs/defschema ItemResponse
      "The cost of the identified item."
      {:cost (rs/with-usage-description "Cost of item with taxes and discounts." ItemCost)})

    (it "can process nested schemas"
        (let [[swagger-object schema] (sw/->swagger-schema sw/default-swagger-options {} ItemResponse)]
          (should= {:definitions
                    {"rook.swagger-spec:ItemCost"     {:type        :object
                                                       :description "The cost of an item."
                                                       :properties
                                                                    {:currency {:type        :string
                                                                                :description "Three digit currency code."}
                                                                     :amount   {:type        :string
                                                                                :description "Numeric value."}}
                                                       :required    [:amount]}
                     "rook.swagger-spec:ItemResponse" {:type        :object
                                                       :description "The cost of the identified item."
                                                       :properties  {:cost {"$ref"       "#/definitions/rook.swagger-spec:ItemCost"
                                                                            :description "Cost of item with taxes and discounts."
                                                                            }}
                                                       :required    [:cost]}}} swagger-object)
          (should= {"$ref" "#/definitions/rook.swagger-spec:ItemResponse"} schema)))

    (it "recognizes the Swagger meta-data"
        (let [[swagger-object schema] (sw/->swagger-schema
                                        sw/default-swagger-options
                                        {}
                                        {:tricky (rs/with-description "Tricky!"
                                                                      (rs/with-data-type {:type   :string
                                                                                          :format :password}
                                                                                         (s/both s/Str
                                                                                                 (s/pred #(< 5 (.length %)) 'min-length))))})]
          (should= {} swagger-object)
          (should= {:type     :object
                    :properties
                              {:tricky {:type :string :format :password :description "Tricky!"}}
                    :required [:tricky]} schema)))

    (rs/defschema OptionalItemResponse
      "Optional item cost."
      {:cost (s/maybe ItemCost)})

    (it "marks s/maybe with x-nullable"
        (let [[swagger-object schema] (sw/->swagger-schema sw/default-swagger-options {} OptionalItemResponse)]
          (should= {:definitions
                    {"rook.swagger-spec:ItemCost" {:type        :object
                                                   :description "The cost of an item."
                                                   :properties
                                                                {:currency {:type        :string
                                                                            :description "Three digit currency code."}
                                                                 :amount   {:type        :string
                                                                            :description "Numeric value."}}
                                                   :required    [:amount]}
                     "rook.swagger-spec:OptionalItemResponse"
                                                  {:type        :object
                                                   :description "Optional item cost."
                                                   :properties  {:cost {"$ref"       "#/definitions/rook.swagger-spec:ItemCost"
                                                                        :description "The cost of an item."
                                                                        :x-nullable  true}}
                                                   :required    [:cost]}}} swagger-object)
          (should= {"$ref" "#/definitions/rook.swagger-spec:OptionalItemResponse"} schema))))

  (context "requests"
    (it "can capture header parameters and descriptions"
        ;; We could set this up as in other cases, but the meta-data and interactions are complex
        ;; enough that we should drive from an "integrated" example:
        (->>
          (get-in @swagger [:paths "/hotels/{id}" "put" "parameters"])
          (filter #(= "if-match" (:name %)))
          first
          (should= {:description "Used for optimistic locking."
                    :name        "if-match"
                    :type        :string
                    :in          :header})))

    (it "can capture path parameter descriptions"
        (should= [{:name        "id"
                   :type        :string
                   :in          :path
                   :required    true
                   :description "Unique id of hotel."}]
                 (get-in @swagger [:paths "/hotels/{id}" "get" "parameters"]))))

  (context "responses"

    (with-all response-description
              (rs/with-description "Something Went Boom"
                                   {:error                    (rs/with-description "logical error name" s/Str)
                                    (s/optional-key :message) (rs/with-description "user presentable message" s/Str)}))

    (it "can capture schema descriptions"
        (should= {:path {:responses {200 {:description "Something Went Boom"
                                          :schema      {:required   [:error]
                                                        :description "Something Went Boom"
                                                        :type :object
                                                        :properties {:error   {:type        :string
                                                                               :description "logical error name"}
                                                                     :message {:type        :string
                                                                               :description "user presentable message"}}}}}}}

                 (sw/default-responses-injector sw/default-swagger-options {} {:meta {:responses {200 @response-description}}} [:path]))))

  (context "end-to-end (synchronous)"

    (with-all handler (-> (rook/namespace-handler {:swagger-options swagger-options}
                                                  ["hotels" 'hotels
                                                   [[:hotel-id "rooms"] 'rooms]])
                          rook/wrap-with-standard-middleware))

    (with-all server (jet/run-jetty {:ring-handler @handler
                                     :join?        false
                                     :port         8192}))

    (it "can start the server"
        (should-not-be-nil @server))

    (after-all
      (.stop @server))

    (it "can expose the resource listing"

        ;; The result is so large and complex, there isn't much we can do to check it here.
        (->> (get* "/swagger.json")
             (should-have-status HttpServletResponse/SC_OK)))))

(run-specs)
