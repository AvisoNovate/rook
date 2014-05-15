(ns io.aviso.rook.internals
  "Unsupported internal functions used in the implementation."
  (:require
    [clout.core :as clout]
    [io.aviso.rook.utils :as utils]))

(def ^:private default-mappings
  "Default mappings for route specs to functions. We use keyword for function name for increased readability.
  If a public method whose name matches a default mapping exists, then it will be added using the
  default mapping; for example, a method named \"index\" will automatically be matched against \"GET /\".
  This can be overriden by providing meta-data on the functions."
  {
    'new     [:get "/new"]
    'edit    [:get "/:id/edit"]
    'show    [:get "/:id"]
    'update  [:put "/:id"]
    'patch   [:patch "/:id"]
    'destroy [:delete "/:id"]
    'index   [:get "/"]
    'create  [:post "/"]
    }
  )

(def supported-methods #{:get :put :patch :post :delete :head :all})

(defn to-clojureized-keyword
  "Converts a keyword with embedded underscores into one with embedded dashes."
  [kw]
  (-> kw
      name
      (.replace \_ \-)
      keyword))

(defn to-api-keyword
  "Converts a keyword with embedded dashes into one with embedded underscores."
  [kw]
  (-> kw
      name
      (.replace \- \_)
      keyword))

(defn extract-argument-value
  "Uses the arg-resolvers to identify the resolved value for an argument. First a check for
  the keyword version of the argument (which is a symbol) takes place. If that resolves as nil,
  a second search occurs, using the API version of the keyword (with dashes converted to underscores)."
  [argument request arg-resolvers]
  (let [arg-kw (keyword (if (map? argument)
                          (-> argument
                              :as
                              (or (throw (IllegalArgumentException. "map argument has no :as key")))
                              name)
                          (name argument)))]
    (or
      (some #(% arg-kw request) arg-resolvers)
      (let [api-kw (to-api-keyword arg-kw)]
        (if-not (= arg-kw api-kw)
          (some #(% api-kw request) arg-resolvers))))))

(defn- is-var-a-function?
  "Checks if a var resolved from a namespace is actually a function."
  [v]
  (-> v
      deref
      ifn?))

(defn- ns-function
  "Return the var for the given namespace and function keyword."
  [namespace function-key]
  (when-let [v (ns-resolve namespace (symbol (name function-key)))]
    (when (is-var-a-function? v) ;it has to be a function all right
      v)))

(defn- function-entry
  "Create function entry if it has :path-spec defined in its metadata, for example:

  (defn activate
   {:path-spec [:post \"/:id/activate\"]}
  [id]
   ...
   )"
  [sym]
  (let [symbol-meta (meta sym)
        name (:name symbol-meta)
        path-spec (or (:path-spec symbol-meta) (get default-mappings name))]
    (when-let [[method path] path-spec]
      [method path (keyword (:name symbol-meta))])))

(defn- ns-paths
  "Returns paths for <namespace> using DEFAULT_MAPPINGS and by scanning for functions :path-spec metadata.
  The return value is a seq of tuples contaiing a path-spec tuple and the keyword for a function."
  [namespace-name]
  (->> namespace-name
       ns-publics
       vals
       (filter is-var-a-function?)
       (map function-entry)
       ;; Remove functions that do not have :path-spec metadata, and don't match a convention name
       (remove nil?)))

(defn- eval-namespace-meta-values
  [n meta-map]
  (binding [*ns* n]
    (utils/transform-values meta-map eval)))

(defn get-available-paths
  "Scan namespace for available routes - only those that have available function are returned.

  Routes are sorted by the line number from metadata - which can be troubling if you have the same namespace in many files.

  But then, unless your name is Rich, you're in trouble already... "
  [namespace-name]
  (when-not (find-ns namespace-name)
    (require namespace-name))
  ;; namespace meta-data is not evaluated
  (let [n (find-ns namespace-name)
        meta-eval (partial eval-namespace-meta-values n)
        inherited-meta-data (-> n meta (dissoc :doc) meta-eval)]
    (->> (ns-paths namespace-name)
         (map (fn [[route-method route-path function-key]]
                (when-let [f (ns-function namespace-name function-key)]
                  [route-method route-path f (merge inherited-meta-data (meta f))])))
         (remove nil?)
         ;; sadly, the namespace stores interned values
         (sort-by (fn [[_ _ _ full-meta]] (-> full-meta :line (or 0)))))))

(defn- method-matches?
  [request route-method]
  (or (= :all route-method)
      (= route-method (:request-method request))))

(defn- match-request-to-compiled-path
  [request namespace-name [route-method route-path f full-meta]]
  (when-let [route-params (and (method-matches? request route-method)
                               (clout/route-matches route-path request))]
    (-> request
        ;; Merge params previously identified by Clout/Compojure with those identified
        ;; by this particular mathc.
        (update-in [:route-params] merge route-params)
        (update-in [:rook] merge {:namespace namespace-name
                                  :function  f
                                  :metadata  full-meta}))))

(defn match-against-compiled-paths
  "Uses the compiled paths to identify the matching function to be invoked and returns
  the rook data to be added to the request. Returns nil on no match, or a modified request map
  when a match is found."
  [request namespace-name compiled-paths]
  (some (partial match-request-to-compiled-path request namespace-name) compiled-paths))

(defn to-message [^Throwable t]
  (or (.getMessage t)
      (-> t .getClass .getName)))