(ns rook.dispatcher-spec
  (:use speclj.core
        clojure.pprint)
  (:require [io.aviso.rook.dispatcher :as d]
            [io.aviso.rook.utils :as utils]
            [io.aviso.rook :as rook]
            [io.aviso.rook.schema-validation :as sv]
            [qbits.jet.server :as jet]
            [clj-http.client :as http]
            [clj-http.cookies :as cookies]
            [ring.mock.request :as mock]
            [ring.middleware.session :as session]
            ring.middleware.params
            ring.middleware.keyword-params
            [clojure.edn :as edn]
            [io.aviso.rook.server :as server])
  (:import [javax.servlet.http HttpServletResponse]))


(defn generate-huge-resource-namespace [ns-name size]
  (if-let [ns (find-ns ns-name)]
    (throw
      (ex-info
        (str
          "It so happens that we already have a namespace named "
          ns-name
          "; I probably shouldn't touch it.")
        {:ns-name ns-name :ns ns})))
  (let [ns (create-ns ns-name)]
    (doseq [i (range size)
            :let [foo (str "foo" i)]]
      (intern ns
              (with-meta (symbol foo)
                         {:route    [:get [foo :x]]
                          :arglists (list '[request x])})
              (fn [request x]
                {:status  200
                 :headers {}
                 :body    (str foo "/" x)})))))


(create-ns 'example.foo)

(binding [*ns* (the-ns 'example.foo)]
  (eval '(do
           (clojure.core/refer-clojure)
           (require '[ring.util.response :as resp])
           (defn index []
             (resp/response "Hello!"))
           (defn show [id]
             (resp/response (str "Interesting id: " id)))
           (defn create [^:param x]
             (resp/response (str "Created " x))))))

(create-ns 'example.bar)

(binding [*ns* (the-ns 'example.bar)]
  (eval '(do
           (clojure.core/refer-clojure)
           (require '[ring.util.response :as resp])
           (defn index []
             (resp/response "Hello!"))
           (defn show [id]
             (resp/response (str "Interesting id: " id)))
           (defn create [^:param x]
             (resp/response (str "Created " x))))))

(defn namespace->handler
  "Converts a single namespace (identified as a symbol) into a Ring handler."
  [namespace]
  (->> (d/construct-namespace-handler {} [[namespace]])
       first
       ring.middleware.keyword-params/wrap-keyword-params
       ring.middleware.params/wrap-params))

(defn default-middleware
  "Pretty much same as the one in dispatcher, but seperate to faciliate some of the tests."
  [handler middleware]
  handler)

(def simple-dispatch-table
  [[:get ["foo"] 'example.foo/index default-middleware]
   [:post ["foo"] 'example.foo/create default-middleware]
   [:get ["foo" :id] 'example.foo/show default-middleware]])

{:default :keyword
 'default :symbol}

(defn override-middleware [])

(def mock-default-arg-resolvers
  {:default :keyword
   'default :symbol})

(describe "io.aviso.rook.dispatcher"

  (describe "build-namespace-table"

    (it "allows missing values"
        (->> (d/build-namespace-table [] mock-default-arg-resolvers :default-middleware
                                      [['just.the.namespace]])
             (should= [[[]
                        'just.the.namespace
                        mock-default-arg-resolvers
                        :default-middleware]])))

    (it "builds the namespace context from the root context"
        (->> (d/build-namespace-table ["api"] mock-default-arg-resolvers :default-middleware
                                      [["just" 'just.the.namespace]])
             (should= [[["api" "just"]
                        'just.the.namespace
                        mock-default-arg-resolvers
                        :default-middleware]])))

    (it "prefers the namespace's middleware to the default"
        (->> (d/build-namespace-table [] mock-default-arg-resolvers :default-middleware
                                      [['just.the.namespace override-middleware]])
             (should= [[[]
                        'just.the.namespace
                        mock-default-arg-resolvers
                        override-middleware]])))

    (it "merges the namespace argument resolvers with the default argument resolvers"
        (->> (d/build-namespace-table [] mock-default-arg-resolvers :default-middleware
                                      [['just.the.namespace ^:replace {:override true}]])
             (should= [[[]
                        'just.the.namespace
                        {:override true}
                        :default-middleware]])))

    (it "processes multiple namespaces"
        (->> (d/build-namespace-table ["api"] mock-default-arg-resolvers :default-middleware
                                      [["alpha" 'namespace.alpha]
                                       ["beta" 'namespace.beta]])
             (should= [[["api" "alpha"]
                        'namespace.alpha
                        mock-default-arg-resolvers
                        :default-middleware]
                       [["api" "beta"]
                        'namespace.beta
                        mock-default-arg-resolvers
                        :default-middleware]])))

    (it "throws an exception for an unrecognized method in a route"
        (->>
          (d/construct-namespace-handler {} [['invalid-route]])
          (should-throw Throwable "HTTP method :update is not supported. Supported methods are: :delete, :get, :head, :options, :patch, :post, :put, or :all (to match regardless of method).")))

    (it "passes containing namespace values as defaults to nested"
        (->> (d/build-namespace-table ["api"] mock-default-arg-resolvers :default-middleware
                                      [["alpha" 'namespace.alpha ^:replace {:alpha :one} override-middleware
                                        ["one" 'namespace.alpha-1]
                                        ["two" 'namespace.alpha-2]]
                                       ["beta" 'namespace.beta]])
             (should= [[["api" "alpha"]
                        'namespace.alpha
                        {:alpha :one}
                        override-middleware]
                       [["api" "alpha" "one"]
                        'namespace.alpha-1
                        {:alpha :one}
                        override-middleware]
                       [["api" "alpha" "two"]
                        'namespace.alpha-2
                        {:alpha :one}
                        override-middleware]
                       [["api" "beta"]
                        'namespace.beta
                        mock-default-arg-resolvers
                        :default-middleware]]))))

  (describe "compiled handlers using map traversal"

    (for [[method path extra-params expected-value]
          (partition 4 [:get "/?limit=100" {} "limit=100"
                        :get "/" {} "limit="
                        :get "/123" {} "id=123"
                        :get "/123/activate" {} nil
                        :put "/" {} nil
                        :put "/123" {} nil

                        :post "/123/activate"
                        {:test1 "foo" :test2 "bar"}
                        "test1=foo,id=123,test2=bar,test3=TEST#,test4=test$/123/activate,meth=:post"])]
      (it (format "should return %s from %s %s%s"
                  (pr-str expected-value)
                  (-> method name .toUpperCase)
                  path
                  (if-not (empty? extra-params)
                    (str " with " (pr-str extra-params))
                    ""))
          (should= expected-value
                   (let [mw (fn [handler metadata]
                              (-> handler
                                  ring.middleware.keyword-params/wrap-keyword-params
                                  ring.middleware.params/wrap-params))
                         [handler] (d/construct-namespace-handler nil [['rook-test mw]])
                         body #(:body % %)]
                     (-> (mock/request method path)
                         (update-in [:params] merge extra-params)
                         handler
                         ;; TODO: fix rook-spec and rook-test/activate (the
                         ;; latter should return a response map rather than a
                         ;; string) and switch back to :body
                         body)))))

    (describe "conflicts and versioning"

      (it "should throw exception when endpoints conflict"
          (let [handler (namespace->handler 'unresolved-conflict)]
            (->> (mock/request :get "/123")
                 handler
                 (should-throw Exception "Request GET /123 matched 2 endpoints."))))


      (it "identify endpoint using :match filter"
          (let [handler (namespace->handler 'resolved-conflict)]
            (->> (mock/request :get "/123" {"v" "2"})
                 handler
                 :body
                 :matched
                 (should= "second-match")))))

    (describe "argument resolution"

      (it "supports overriding default arg resolvers"

          (let [override ^:replace-resolvers {'magic-value (constantly "**magic**")}
                handler (rook/namespace-handler {:arg-resolvers override}
                                                [["magic"] 'magic])]
            (-> (mock/request :get "/magic")
                handler
                (should= "**magic**"))))

      (it "supports overriding default arg resolver factories"
          (let [override ^:replace-factories {:magic (fn [sym]
                                                       (constantly (str "**presto[" sym "]**")))}
                handler (rook/namespace-handler {:arg-resolvers override}
                                                [["presto"] 'presto])]
            (-> (mock/request :get "/presto/42")
                handler
                (should= "42 -- **presto[extra]**"))))))

  (describe "handlers with a large number of endpoints"

    (it "should compile and handle requests as expected using map traversal"
        (should= {:status 200 :headers {} :body "foo0/123"}
                 ((let [size 500]
                    (remove-ns 'rook.example.huge)
                    (generate-huge-resource-namespace 'rook.example.huge size)
                    (rook/namespace-handler ['rook.example.huge]))
                   {:request-method :get
                    :uri            "/foo0/123"
                    :server-name    "127.0.0.1"
                    :port           8080
                    :remote-addr    "127.0.0.1"
                    :scheme         :http
                    :headers        {}}))))

  (describe "running end-to-end with Jetty (via Jet)"

    (with-all server
              (let [creator #(->
                              (rook/namespace-handler
                                {:default-middleware sv/wrap-with-schema-validation
                                 :arg-resolvers      {'strange-injection :injection}
                                 ;; just to make sure Swagger support doesn't break things
                                 :swagger            true}
                                ["fred" 'fred]
                                ["barney" 'barney]
                                ["betty" 'betty]
                                ["slow" 'slow]
                                ["sessions" 'sessions]
                                ["creator" 'creator]
                                ["creator-loopback" 'creator-loopback]
                                ["static" 'static]
                                [["static2" :foo "asdf"] 'static2 d/default-namespace-middleware]
                                ["catch-all" 'catch-all d/default-namespace-middleware]
                                ["surprise" 'surprise d/default-namespace-middleware
                                 [[:id "foo"] 'surprise-foo]]
                                ["foobar" 'foobar])
                              session/wrap-session
                              (rook/wrap-with-injection :strange-injection "really surprising"))
                    handler (server/construct-handler {:debug     true
                                                       :standard  true
                                                       :exception true}
                                                      creator)]
                (jet/run-jetty {:host         "localhost"
                                :port         9988
                                :join?        false
                                :ring-handler handler})))

    (it "initializes the server successfully"
        (should-not-be-nil @server))

    (it "can process requests and return responses"
        (let [response (http/get "http://localhost:9988/betty/rubble-1" {:accept :json})]
          (should= HttpServletResponse/SC_OK
                   (:status response))
          (should= "application/json; charset=utf-8"
                   (-> response :headers (get "Content-Type")))
          (should= "{\"message\":\"rubble-1 is a very fine id!\"}" (:body response))))

    (it "can manage server-side session state"
        (let [k (utils/new-uuid)
              v (utils/new-uuid)
              uri "http://localhost:9988/sessions/"
              store (cookies/cookie-store)

              response (http/post (str uri k "/" v)
                                  {:accept       :edn
                                   :cookie-store store})
              response' (http/get (str uri k)
                                  {:accept           :edn
                                   :cookie-store     store
                                   :throw-exceptions false})]
          (should= 200 (:status response))
          (should= (pr-str {:result :ok}) (:body response))
          (should= 200 (:status response'))
          (should= v (-> response' :body edn/read-string :result))))

    (it "responds with 404 if no handler can be found"
        (let [response (http/get "http://localhost:9988/wilma"
                                 {:throw-exceptions false})]
          (should= HttpServletResponse/SC_NOT_FOUND (:status response))))

    (it "should resolve arguments statically given appropriate metadata"
        (let [response (http/get "http://localhost:9988/static"
                                 {:accept :edn})]
          (should= "Server localhost has received a request for some application/edn."
                   (:body response))))

    (it "should correctly handle route params specified in context vectors"
        (let [response (http/get "http://localhost:9988/static2/123/asdf/foo")]
          (should= "Here's the foo param for this request: 123"
                   (:body response))))

    (it "should correctly handle catch-all routes (:all)"
        (let [response1 (http/get "http://localhost:9988/catch-all")
              response2 (http/put "http://localhost:9988/catch-all")]
          (should= "Caught you!" (:body response1))
          (should= "Caught you!" (:body response2))))

    (it "should support injections and default argument resolvers"
        (let [response (http/get "http://localhost:9988/surprise")]
          (should= "This is really surprising!" (:body response))))

    (it "should support nested ns-specs in namespace-handler calls with context route params"
        (let [response (http/get "http://localhost:9988/surprise/123/foo")]
          (should= "Surprise at id 123!" (:body response))))

    (it "should support routes using different route param names in the same position"
        (let [foo-response (http/get "http://localhost:9988/foobar/123/foo")
              bar-response (http/get "http://localhost:9988/foobar/456/bar")]
          (should= "foo-id is 123" (:body foo-response))
          (should= "bar-id is 456" (:body bar-response))))

    (after-all
      (.stop @server))))

(run-specs)