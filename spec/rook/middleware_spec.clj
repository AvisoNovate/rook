(ns rook.middleware-spec
  (:use
    speclj.core
    io.aviso.rook))

(def terminal-handler identity)

(defn wrap-with-gnip
  [handler metadata]
  (if-let [value (:gnip metadata)]
    (fn [request]
      (handler (assoc request :gnip value)))))

(defn wrap-with-plug
  [handler metadata tag]
  (if (:plug metadata)
    (fn [request]
      (-> request
          (update-in [:plug] (fnil conj []) tag)
          handler))))

(def request {:uri "/"})

(describe "io.aviso.rook/middleware->"

  (it "can wrap a handler with a middleware"
      (let [middleware (middleware-> wrap-with-gnip)
            handler' (middleware terminal-handler {:gnip :gnop})]
        (should-not-be-same terminal-handler handler')
        (should= {:uri "/" :gnip :gnop}
                 (handler' request))))

  (it "leaves a handler alone if middleware returns nil"
      (let [middleware (middleware-> wrap-with-gnip)
            handler' (middleware terminal-handler {})]
        (should-be-same terminal-handler handler')
        (should= request
                 (handler' request))))

  (it "can pass parameters to the middleware"
      (let [middleware (middleware->
                         (wrap-with-plug :alpha)
                         (wrap-with-plug :beta)
                         (wrap-with-plug :gamma))
            handler' (middleware terminal-handler {:plug true})]
        (should= {:uri "/" :plug [:gamma :beta :alpha]}
                 (handler' request)))))

(run-specs)
