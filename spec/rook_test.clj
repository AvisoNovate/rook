(ns rook-test)

(defn index [limit]
  {:status 200
   :body   (str "limit=" limit)})

(defn show [id]
  {:status 200
   :body   (str "id=" id)})

(defn activate
  {:route-spec [:post [:id "activate"]]
   :path-spec [:post "/:id/activate"]}
  [test1 id request test2 test3 test4 request-method]
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
  {:path-spec [:post "/:id/if-modified-since"]}
  [id if-modified-since]
  {:id id
   :if-modified-since if-modified-since})
