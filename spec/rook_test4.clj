(ns rook-test4)

(defn proxy-request
  {:path-spec [:all "/proxy"]}
  [^:request-key request-method]
  (str "method=" (-> request-method name .toUpperCase)))
