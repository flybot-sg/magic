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

;;; nos dev: the Editor's dev set and roots file, built by a child nos in a
;;; throwaway project, since a build resets this process's load path.

(defn- write-file [dir rel text]
  (let [path (System.IO.Path/Combine dir rel)]
    (System.IO.Directory/CreateDirectory (System.IO.Path/GetDirectoryName path))
    (System.IO.File/WriteAllText path text)))

;; Under the cwd, not the temp dir, whose path macOS reaches through a symlink
;; the child's cwd would resolve.
(defn- dev-project []
  (let [dir (System.IO.Path/GetFullPath (str "target/nos-dev-" (gensym)))]
    (write-file dir "deps.edn" "{:paths [\"src\"] :deps {dev/lib {:local/root \"lib\"}}}")
    (write-file dir "magic.edn" "{:build {:namespaces [dev.a]}}")
    (write-file dir "lib/deps.edn" "{:paths [\"lsrc\"]}")
    (write-file dir "lib/lsrc/dev/lib.clj" "(ns dev.lib)")
    (write-file dir "src/dev/a.clj" "(ns dev.a (:require dev.lib)) (def v 1)")
    dir))

(defn- nos-dev
  "Run `nos dev` in dir, without the CLOJURE_LOAD_PATH this nos exports, which
  the child would take as source roots."
  [dir]
  (let [info (doto (System.Diagnostics.ProcessStartInfo.
                    (.. System.Diagnostics.Process GetCurrentProcess MainModule FileName)
                    (str "\"" (.Location (System.Reflection.Assembly/GetEntryAssembly)) "\" dev"))
               (.set_UseShellExecute false)
               (.set_RedirectStandardOutput true)
               (.set_RedirectStandardError true)
               (.set_WorkingDirectory dir))
        _    (.Remove (.EnvironmentVariables info) "CLOJURE_LOAD_PATH")
        proc (System.Diagnostics.Process/Start info)
        err  (.ReadToEndAsync (.StandardError proc))
        out  (.ReadToEnd (.StandardOutput proc))]
    (.WaitForExit proc)
    (is (= 0 (.ExitCode proc)) (str out (.Result err)))))

(defn- file-hash [dir rel]
  (with-open [md5 (System.Security.Cryptography.MD5/Create)]
    (System.BitConverter/ToString
     (.ComputeHash md5 (System.IO.File/ReadAllBytes (System.IO.Path/Combine dir rel))))))

(deftest dev-rebuilds-an-edited-namespace
  (let [dir (dev-project)
        dll "Library/MagicDev/dev.a.clj.dll"]
    (try
      (nos-dev dir)
      (let [before (file-hash dir dll)]
        (write-file dir "src/dev/a.clj" "(ns dev.a (:require dev.lib)) (def v 2)")
        (nos-dev dir)
        (is (not= before (file-hash dir dll))))
      (finally (System.IO.Directory/Delete dir true)))))

(deftest dev-writes-the-source-roots
  (let [dir (dev-project)]
    (try
      (nos-dev dir)
      (is (= [(System.IO.Path/Combine dir "src") (System.IO.Path/Combine dir "lib/lsrc")]
             (vec (System.IO.File/ReadAllLines (System.IO.Path/Combine dir "Library/MagicDevRoots.txt")))))
      (finally (System.IO.Directory/Delete dir true)))))
