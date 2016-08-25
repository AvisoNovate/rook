(ns sample.form-endpoints
  (:require [ring.util.response :refer [response]]
            [io.aviso.rook.interceptors :refer [keywordized-form]]))

(defn post-new-widget
  {:rook-route [:post ""]
   ;; This is something I like; form parsing and all that ONLY occurs if this is the selected
   ;; endpoint. Otherwise, the :body is never accessed. And for stateless and
   ;; configuration-free interceptors like
   ;; this one, we don't even need to use the extra machinery Rook provides.
   :interceptors [keywordized-form]}
  [^:form-param widget-name
   ^:form-param supplier-id]
  (response {:widget-name widget-name
             :supplier-id supplier-id}))
