(ns io.aviso.rook.swagger

  "ALPHA / EXPERIMENTAL

  Adapter for ring-swagger. Converts an intermediate description of the namespaces
  into a description compatible with the ring-swagger library."
  {:since "0.1.14"
   :sync  true}
  (:require [ring.swagger.core :as swagger]
            [ring.swagger.ui :as ui]
            [clojure.string :as string]
            [io.aviso.rook :as rook]
            [clojure.string :as str]
            [io.aviso.rook.utils :as utils]))

;;; 'swagger presumed resolvable (namespace-handler will provide an
;;; injection if asked to swaggerize the handler)

(defn index
  [swagger]
  (-> swagger/swagger-defaults
      (assoc :apis (for [[path {:keys [description]}] swagger]
                     {:path        path
                      :description description}))
      (utils/response)))

(defn show
  [request swagger id]
  (swagger/api-declaration {} swagger id
                           (swagger/basepath request)))

(defn- nickname [sym]
  (string/replace (str (namespace sym) "__" (name sym)) #"[/.-]" "_"))

(defn- prefix [pathvec]
  (string/join "_"
               (map #(if (or (keyword? %) (symbol? %))
                      (str "_" (name %))
                      %)
                    pathvec)))

(defn- get-success
  ([responses]
   (get-success responses nil))
  ([responses not-found]
   (let [s? (ffirst (drop-while #(< (first %) 200) (sort-by first responses)))]
     (if (and s? (< s? 300))
       s?
       not-found))))

(defn- ->swagger-term [value]
  (if (keyword? value)
    (str \{ (name value) \})
    value))

(defn- ->swagger-path
  "Create a string from a dispatcher path, which consists of strings and keywords.
  Result will have a leading slash."
  [path]
  (->>
    (map ->swagger-term path)
    (str/join "/")
    (apply str "/")))

;;; FIXME
(defn- header? [arg]
  (:header (meta arg)))

;;; FIXME
(defn- param? [arg]
  (:param (meta arg)))

;;; FIXME
;; My current thoughts are that the argument resolvers should provide
;; metadata for each argument that can be passed through here, to identify
;; the Swagger data for the parameter.  Injections and the like would
;; simply be ignored.
(defn- arglist-swagger [schema route-params arglist]
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

(defn- routing-specs->swagger-routes
  [routing-specs]
  (map (fn [[method path _ endpoint-meta]]
         (let [responses (:responses endpoint-meta)
               route-params (->> path
                                 (filter keyword?)
                                 (map (comp symbol name))
                                 set)
               schema (:schema endpoint-meta)
               arglist (-> endpoint-meta :arglists first)
               swagger-args (arglist-swagger schema route-params arglist)]
           {:method   method
            :uri      (->swagger-path path)
            :metadata {:summary          (or (:doc endpoint-meta) "No endpoint description provided.")
                       :return           (get-success responses)
                       :responseMessages (for [[status schema] responses]
                                           {:code          (long status)
                                            :message       ""
                                            :responseModel schema})
                       :parameters       swagger-args}}))
       routing-specs))

(defn construct-swagger
  "Provided with the routing table produced by
  [[io.aviso.rook.dispatcher/construct-namespace-handler]]
  and constructs a the swagger datastructure that will be provided
  as an injection to the various endpoint functions in this namespace.

  Formal parameters resolved to :param will be annotated as being of
  Swagger paramType \"query\". Formal parameters resolved to
  injections and the like will be omitted."
  [routing-table]
  (reduce
    (fn [swagger [[context _ _ _ ns-meta] routing-specs]]
      (assoc swagger
        (->swagger-path context)
        {:description (or (:doc ns-meta) "No resource description provided.")
         :routes      (routing-specs->swagger-routes routing-specs)}))
    {}
    routing-table))

(defn wrap-with-swagger-ui
  "Gives swagger-ui the opportunity to handle a request before passing
  it on to the wrapped handler."
  [handler routing-table]
  (let [swagger (construct-swagger routing-table)
        swagger-ui (ui/swagger-ui "/swagger-ui" :swagger-docs "/swagger")
        handler' (fn [request]
                   (or (swagger-ui request)
                       (handler request)))]
    ;; Remove the mapping for swagger itself form what's exposed.
    (rook/wrap-with-injection handler' :swagger (dissoc swagger "/swagger"))))

(defmethod ring.swagger.json-schema/json-type Integer [_]
  {:type "integer" :format "int32"})
