(ns io.aviso.rook.internals
  "Unsupported internal functions used in the implementation."
  (:require [clout.core :as clout]))

(def ^:private default-mappings
  "Default mappings for route specs to functions. We use keyword for function name for increased readability.
  If a public method whose name matches a default mapping exists, then it will be added using the
  default mapping; for example, a method named \"index\" will automatically be matched against \"GET /\".
  This can be overriden by providing meta-data on the functions."
  {
    'new [:get "/new"]
    'edit [:get "/:id/edit"]
    'show [:get "/:id"]
    'update [:put "/:id"]
    'patch [:patch "/:id"]
    'destroy [:delete "/:id"]
    'index [:get "/"]
    'create [:post "/"]
    }
  )

(def supported-methods #{:get :put :patch :post :delete :head :all})

(defn extract-argument-value
  "Return parameter values for handler function based on request data. The order of parameter resolution is following:
  => request parameter gets mapped to the request
  => data parameter gets mapped to the parsed and validated request data (if available)
  => parameters found in (:route-params request) are mapped then
  => then we use POST/GET parameters from (:params) (we assume that they are keywordized using appropriate middleware)"
  [argument request arg-resolvers]
  (let [arg-kw (keyword (name argument))
        api-kw (keyword (.replace (name argument) "-" "_"))]
    (or
      (some #(% arg-kw request) arg-resolvers)
      (when (= :request arg-kw) request)
      (get (:route-params request) api-kw)
      (get (:route-params request) arg-kw)
      (get (:params request) api-kw)
      (get (:params request) arg-kw))))

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

(defn get-available-paths
  "Scan namespace for available routes - only those that have available function are returned.

  Routes are sorted by the line number from metadata - which can be troubling if you have the same namespace in many files.

  But then, unless your name is Rich, you're in trouble already... "
  [namespace-name]
  (when-not (find-ns namespace-name)
    (require namespace-name))
  (let [inherited-meta-data (-> namespace-name find-ns meta (dissoc :doc))]
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
        (update-in [:rook] merge {:namespace     namespace-name
                                  :function      f
                                  :metadata      full-meta
                                  :arg-resolvers (:arg-resolvers (meta f))}))))

(defn match-against-compiled-paths
  "Uses the compiled paths to identify the matching function to be invoked and returns
  the rook data to be added to the request. Returns nil on no match, or a modified request map
  when a match is found."
  [request namespace-name compiled-paths]
  (some (partial match-request-to-compiled-path request namespace-name) compiled-paths))
