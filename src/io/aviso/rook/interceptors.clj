(ns io.aviso.rook.interceptors
  "Common interceptors used with Rook."
  (:require [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.http.body-params :as body-params]))

(def keywordized-form
  "A wrapper around form parsing; the request's body is parsed,
  and a :form-params key is added to the request. The keys in the map
  are keywordized."
  (interceptor
    {:name ::keywordized-form
     :enter (fn [context]
              (update context :request body-params/form-parser))}))
