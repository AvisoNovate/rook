(ns rook-test4)

(defn proxy-request
  {:route [:all ["proxy"]]}
  [^:request-key request-method]
  (str "method=" (-> request-method name .toUpperCase)))
