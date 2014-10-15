(ns integration_spec
	(:require [speclj.core :refer :all]
						[speclj.run.standard]
						[clj-http.client :as client]))

(describe "integration"
	(it "responds to a request"
    ;; TODO: This is the *very* hard way to run things.
    ;; We should just be able to use the org.example.server
    ;; namespace to start (and then stop) the Jetty instance.
		(let [command   "lein run"
					proc      (.exec (Runtime/getRuntime) command)
				  input     (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream proc)))]
				(should= "Running." (.readLine input))
				(let [response (client/get "http://localhost:8080/counters")]
					(should= {"foo" 0 "bar" 0} (eval (read-string (:body response))))))))

(run-specs)