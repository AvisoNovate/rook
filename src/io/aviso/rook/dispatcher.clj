(ns io.aviso.rook.dispatcher
  (:require [clojure.core.match :refer [match]]
            [clojure.string :as string]
            [clojure.pprint :as pp]
            [io.aviso.rook.internals :as internals]))

(defn pprint-code [form]
  (pp/write form :dispatch pp/code-dispatch))

(defn preparse-request
  "Takes a Ring request map and returns [method pathvec], where method
  is a request method keyword and pathvec is a vector of path
  segments.

  For example,

    GET /foo/bar HTTP/1.1

  becomes

    [:get [\"foo\" \"bar\"]].

  The individual path segments are URL decoded; UTF-8 encoding is
  assumed."
  [request]
  [(:request-method request)
   (mapv #(java.net.URLDecoder/decode ^String % "UTF-8")
     (next (string/split (:uri request) #"/" -1)))])

(defn unnest-dispatch-table
  "Given a nested dispatch table:

    [[method pathvec verb-fn pipeline
      [method' pathvec' verb-fn' pipeline' ...]
      ...]
     ...]

  produces a dispatch table with no nesting:

    [[method pathvec verb-fn pipeline]
     [method' (into pathvec pathvec') verb-fn' pipeline']
     ...]."
  [dispatch-table]
  (letfn [(unnest-entry [[method pathvec verb-fn pipeline
                          & nested-table
                          :as entry]]
            (if nested-table
              (into [[method pathvec verb-fn pipeline]]
                (unnest-table pathvec nested-table))
              [entry]))
          (unnest-table [context-pathvec entries]
            (mapv (fn [[_ pathvec :as unnested-entry]]
                    (assoc unnested-entry 1 (into context-pathvec pathvec)))
              (mapcat unnest-entry entries)))]
    (unnest-table [] dispatch-table)))

(defn keywords->symbols
  "Converts keywords in xs to symbols, leaving other items unchanged."
  [xs]
  (mapv #(if (keyword? %)
           (symbol (name %))
           %)
    xs))

(defn prepare-handler-bindings
  "Used by compile-dispatch-table."
  [request-sym arglist route-params non-route-params]
  (mapcat (fn [param]
            [param
             (if (contains? route-params param)
               `(get (:route-params ~request-sym) ~(keyword param))
               `(internals/extract-argument-value
                  (keyword (quote ~param))
                  ~request-sym
                  (-> ~request-sym :rook :arg-resolvers)))])
    arglist))

(defn compile-dispatch-table
  "Compiles the dispatch table into a Ring handler."
  ([dispatch-table]
     (compile-dispatch-table {} dispatch-table))
  ([options dispatch-table]
     (let [dt  (unnest-dispatch-table dispatch-table)
           req (gensym "request__")
           emit-fn (or (:emit-fn options) eval)]
       (emit-fn
         `(fn rook-dispatcher# [~req]
            (match (preparse-request ~req)
              ~@(mapcat
                  (fn [[method pathvec verb-fn-sym pipeline]]
                    (let [metadata         (meta (resolve verb-fn-sym))
                          pathvec          (keywords->symbols pathvec)
                          route-params     (set (filter symbol? pathvec))
                          arglist          (first (:arglists metadata))
                          non-route-params (remove route-params arglist)]
                      [[method pathvec]
                       `(let [route-params# ~(zipmap
                                               (map keyword route-params)
                                               route-params)
                              handler# (fn [~req]
                                         (let [~@(prepare-handler-bindings
                                                   req
                                                   arglist
                                                   route-params
                                                   non-route-params)]
                                           (~verb-fn-sym ~@arglist)))]
                          ((~pipeline handler#)
                           (assoc ~req
                             :route-params route-params#)))]))
                  dt)
              :else nil))))))

#_
(defmacro define-dispatch-table [name & opts-and-entries]
  (def ~name `(compile-dispatch-table ~@opts-and-entries)))
