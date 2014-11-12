(ns io.aviso.rook.swagger

  "ALPHA / EXPERIMENTAL

  Adapter for ring-swagger. Consumes dispatch table descriptors in the
  format used by [[io.aviso.rook/namespace-handler]]."
  {:since "0.1.14"}
  (:require [io.aviso.rook.dispatcher :as dispatcher]
            [ring.swagger.core :as swagger]
            [ring.swagger.ui :as ui]
            [schema.core :as s]
            [clojure.string :as string]
            [clojure.core.async :as async]))

(defn nickname [sym]
  (string/replace (str (namespace sym) "__" (name sym)) #"[/.-]" "_"))

(defn prefix [pathvec]
  (string/join "_"
               (map #(if (or (keyword? %) (symbol? %))
                      (str "_" (name %))
                      %)
                    pathvec)))

(defn get-success
  ([responses]
   (get-success responses nil))
  ([responses not-found]
   (let [s? (ffirst (drop-while #(< (first %) 200) (sort-by first responses)))]
     (if (and s? (< s? 300))
       s?
       not-found))))

;;; FIXME
(defn header? [arg]
  (:header (meta arg)))

;;; FIXME
(defn param? [arg]
  (:param (meta arg)))

(def ^:private default-opts
  @#'dispatcher/default-opts)

;;; FIXME
(defn arglist-swagger [schema route-params arglist]
  (vec
    (keep (fn [arg]
            (cond
              (contains? route-params arg)
              {:type :path :model {(keyword arg) String}}
              (param? arg)
              {:type :query :model {(keyword arg) (get schema (keyword arg))}}
              (header? arg)
              {:type :header :model {(keyword arg) String}}
              :else nil))
          arglist)))

(defn ns-spec->routes-swagger
  [options ns-spec]
  (let [routes (-> (dispatcher/namespace-dispatch-table options ns-spec)
                   dispatcher/unnest-dispatch-table
                   (dispatcher/analyse-dispatch-table options))]
    (mapv (fn [[method pathvec handler]]
            (let [path (dispatcher/pathvec->path
                         (mapv (fn [x] (if (symbol? x) (keyword x) x))
                               pathvec))
                  {endpoint-metadata :metadata
                   :keys             [schema route-params arglist]} handler
                  ps (arglist-swagger schema (set route-params) arglist)]
              {:method   method
               :uri      path
               :metadata {:summary          (:doc endpoint-metadata)
                          :return           (get-success (:responses endpoint-metadata))
                          :responseMessages (for [[status schema] (:responses endpoint-metadata)]
                                              {:code          (long status)
                                               :message       ""
                                               :responseModel schema})
                          :nickname         (-> handler :verb-fn-sym nickname)
                          :parameters       ps}}))
          routes)))

(defn namespace-swagger
  "Takes ns-specs in the format expected by
  [[io.aviso.rook/namespace-handler]] and returns a
  ring-swagger-compatible description of the API they define.

  The `options` map used with [[io.aviso.rook/namespace-handler]], if
  any, should be passed in to enable correct parameter types to be
  established.

  Formal parameters resolved to :param will be annotated as being of
  Swagger paramType \"query\". Formal parameters resolved to
  injections and the like will be omitted."
  [options ns-specs]
  (let [options' (merge default-opts options)
        default-middleware (:default-middleware options')
        ns-specs (dispatcher/canonicalize-ns-specs
                   []
                   default-middleware
                   (if (map? options)
                     ns-specs
                     (cons options ns-specs)))]
    (reduce (fn [swagger [pathvec ns-sym :as ns-spec]]
              (let [prefix (prefix pathvec)
                    ns-doc (:doc (meta (find-ns ns-sym)))
                    routes-swagger (ns-spec->routes-swagger
                                     options' ns-spec)]
                (-> swagger
                    (assoc-in [prefix :description] ns-doc)
                    (assoc-in [prefix :routes]
                              routes-swagger))))
            {}
            ns-specs)))

;;; 'swagger presumed resolvable (namespace-handler will provide an
;;; injection if asked to swaggerize the handler)

(defn index
  {:sync  true
   :route [:get ["swagger"]]}
  [swagger]
  (swagger/api-listing {} swagger))

(defn show
  {:sync  true
   :route [:get ["swagger" :id]]}
  [request swagger id]
  (swagger/api-declaration {} swagger id
                           (swagger/basepath request)))

(defn swaggerize-ns-specs
  "Adds Swagger endpoints to the given ns-specs. Intended for use by
  [[io.aviso.rook/namespace-handler]]."
  [ns-specs]
  (cons ['io.aviso.rook.swagger dispatcher/default-namespace-middleware] ns-specs))

(def swagger-ui (ui/swagger-ui "/swagger-ui" :swagger-docs "/swagger"))

(defn wrap-with-swagger-ui
  "Gives swagger-ui the opportunity to handle a request before passing
  it on to the wrapped handler. Wraps swagger responses in channels if
  `:async? true` is supplied. (The wrapped handler is presumed async
  in that case and its responses are not wrapped.)"
  ([handler]
   (wrap-with-swagger-ui handler {:async? false}))
  ([handler {:keys [async?]}]
   (if async?
     (fn [request]
       (if-let [swagger-response (swagger-ui request)]
         (async/to-chan [swagger-response])
         (handler request)))
     (fn [request]
       (or (swagger-ui request)
           (handler request))))))

(defmethod ring.swagger.json-schema/json-type Integer [_]
  {:type "integer" :format "int32"})
