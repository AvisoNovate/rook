(ns rook.server-spec
  (:use speclj.core)
  (:require [io.aviso.rook.server :as server])
  (:import [javax.servlet.http HttpServletResponse]))

(describe "io.aviso.rook.server"

  (context "wrap-creator"
    (it "responds with a 500 response if there's an error creating the handler"

        (let [message "For whom the bell tolls"
              creator #(throw (ex-info message {}))
              creator' (server/wrap-creator creator [])
              handler (creator')
              response (handler {})]
          (should= HttpServletResponse/SC_INTERNAL_SERVER_ERROR (:status response))
          (should= message (-> response :body :message))))))

(run-specs)