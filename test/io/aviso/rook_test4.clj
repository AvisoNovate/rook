(ns io.aviso.rook-test4)

(defn proxy-request
  {:path-spec [:all "/proxy"]}
  [request-method]
  (str "method=" (-> request-method name .toUpperCase)))
