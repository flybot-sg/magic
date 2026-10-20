(ns magic.test.nostrand
  (:require [clojure.test :refer [deftest is]]))

(defn- run [& command]
  (Nostrand.Runtime/Run (into-array String command)))

(deftest run-returns-the-task-value
  (is (= [1 :a] (run "clojure.core/vector" "1" ":a"))))

(defn current-ns [] (ns-name *ns*))

(deftest run-runs-the-task-in-user
  (binding [*ns* (the-ns 'magic.test.nostrand)]
    (is (= 'user (run "magic.test.nostrand/current-ns")))
    (is (= 'magic.test.nostrand (ns-name *ns*)))))

(deftest run-throws-a-missing-task
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No task named magic-test-missing"
                        (run "magic-test-missing"))))

(deftest run-throws-a-missing-namespace
  (is (thrown? System.IO.FileNotFoundException (run "magic.test.tmp.missing/f"))))

(deftest run-needs-a-task
  (is (thrown? ArgumentException (run))))
