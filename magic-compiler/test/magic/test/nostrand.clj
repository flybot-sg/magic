(ns magic.test.nostrand
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as string]
            [magic.test.common :refer [nos temp-dir]]
            [nostrand.exec :as exec]))

(deftest main-returns-the-task-value
  (is (= [1 :a] (exec/-main "clojure.core/vector" "1" ":a"))))

(deftest main-throws-a-missing-task
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No task named magic-test-missing"
                        (exec/-main "magic-test-missing"))))

(deftest main-throws-a-missing-namespace
  (is (thrown? System.IO.FileNotFoundException (exec/-main "magic.test.tmp.missing/f"))))

(deftest main-needs-a-task
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Not a task name: nil" (exec/-main))))

(deftest nos-runs-the-task-in-user
  (let [dir (temp-dir)]
    (try
      (System.IO.File/WriteAllText (System.IO.Path/Combine dir "probe.clj")
                                   "(ns probe) (defn current-ns [] (println (ns-name *ns*)))")
      (let [{:keys [exit out]} (nos dir "probe/current-ns")]
        (is (= 0 exit))
        (is (= ["user"] (string/split-lines out))))
      (finally (System.IO.Directory/Delete dir true)))))
