(ns rook-test
  {:arg-resolvers {'test3 (constantly "TEST#")
                   'test4 (fn [request] (str "test$" (:uri request)))}})

(defn index [^:param limit]
  {:status 200
   :body   (str "limit=" limit)})

(defn show [id]
  {:status 200
   :body   (str "id=" id)})

(defn activate
  {:route [:post [:id "activate"]]}
  [^:param test1
   id
   ^:request request
   ^:param test2
   test3
   test4
   ^:request-key request-method]
  (str "test1=" test1
       ",id=" id
       ",test2=" test2,
       ",test3=" test3,
       ",test4=" test4,
       ;; makes some tests brittle when written in the straightforward
       ;; fashion using should=:
       ;;",request=" (count request)
       ",meth=" request-method))

(defn check-if-modified
  {:route [:post [:id "if-modified-since"]]}
  [id ^:header if-modified-since]
  {:id                id
   :if-modified-since if-modified-since})
