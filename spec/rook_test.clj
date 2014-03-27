(ns rook-test)

(defn index [limit]
  {:status 200
   :body   (str "limit=" limit)})

(defn show [id]
  {:status 200
   :body   (str "id=" id)})

(defn activate
  {:path-spec [:post "/:id/activate"]}
  [test1 id request test2 test3 test4 request-method]
  (str "test1=" test1
       ",id=" id
       ",test2=" test2,
       ",test3=" test3,
       ",test4=" test4,
       ",request=" (count request)
       ",meth=" request-method))

