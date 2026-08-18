(ns magic.test.load
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as string]
            [magic.api :as api])
  (:import [System.IO Directory File Path]))

(defn- temp-dir []
  (let [dir (Path/Combine (Path/GetTempPath) (str "magic-test-load-" (gensym)))]
    (Directory/CreateDirectory dir)
    dir))

(defn- write-ns!
  "Write source for namespace sym into dir under the given extension, munging
  the namespace to a relative path the way the loader expects."
  [dir sym ext source]
  (let [relative (-> (str sym) (.Replace "-" "_") (.Replace "." "/"))
        path (Path/Combine dir (str relative ext))]
    (Directory/CreateDirectory (Path/GetDirectoryName path))
    (File/WriteAllText path source)
    path))

(defn- write-unloadable-dll! [dir sym ext]
  (let [path (Path/Combine dir (str (.Replace (str sym) "-" "_") ext ".dll"))]
    (File/WriteAllText path "not an assembly")
    (File/SetLastWriteTime path (.AddMinutes System.DateTime/Now 1))
    path))

(defn- load-in [dir sym]
  (binding [*load-paths* [dir]]
    (-load (-> (str sym) (.Replace "-" "_") (.Replace "." "/")))))

(defn- root-message [^Exception e]
  (if-let [inner (.InnerException e)]
    (recur inner)
    (.Message e)))

(deftest loads-cljr-source
  (let [dir (temp-dir)
        sym (symbol (str "magic.test.tmp.plain" (gensym)))]
    (try
      (write-ns! dir sym ".cljr" (str "(ns " sym ")(def x :from-cljr)"))
      (load-in dir sym)
      (is (= :from-cljr @(ns-resolve (find-ns sym) 'x)))
      (finally (Directory/Delete dir true)))))

(deftest cljr-preferred-over-clj-and-cljc
  (testing "a .cljr file wins over .clj, as on ClojureCLR"
    (let [dir (temp-dir)
          sym (symbol (str "magic.test.tmp.overclj" (gensym)))]
      (try
        (write-ns! dir sym ".cljr" (str "(ns " sym ")(def w :cljr)"))
        (write-ns! dir sym ".clj" (str "(ns " sym ")(def w :clj)"))
        (load-in dir sym)
        (is (= :cljr @(ns-resolve (find-ns sym) 'w)))
        (finally (Directory/Delete dir true)))))
  (testing "a .cljr file wins over .cljc"
    (let [dir (temp-dir)
          sym (symbol (str "magic.test.tmp.overcljc" (gensym)))]
      (try
        (write-ns! dir sym ".cljr" (str "(ns " sym ")(def w :cljr)"))
        (write-ns! dir sym ".cljc" (str "(ns " sym ")(def w :cljc)"))
        (load-in dir sym)
        (is (= :cljr @(ns-resolve (find-ns sym) 'w)))
        (finally (Directory/Delete dir true))))))

(deftest cljc-preferred-over-clj
  (testing "a .cljc file wins over .clj, as on ClojureCLR"
    (let [dir (temp-dir)
          sym (symbol (str "magic.test.tmp.cljcoverclj" (gensym)))]
      (try
        (write-ns! dir sym ".cljc" (str "(ns " sym ")(def w :cljc)"))
        (write-ns! dir sym ".clj" (str "(ns " sym ")(def w :clj)"))
        (load-in dir sym)
        (is (= :cljc @(ns-resolve (find-ns sym) 'w)))
        (finally (Directory/Delete dir true))))))

(deftest assembly-is-paired-with-the-resolved-source
  (testing "a .clj.dll never stands in for the .cljc that resolved"
    (let [dir (temp-dir)
          sym (symbol (str "magic.test.tmp.unpaired" (gensym)))]
      (try
        (write-ns! dir sym ".cljc" (str "(ns " sym ")(def w :cljc)"))
        (write-unloadable-dll! dir sym ".clj")
        (load-in dir sym)
        (is (= :cljc @(ns-resolve (find-ns sym) 'w)))
        (finally (Directory/Delete dir true)))))
  (testing "the .cljc.dll still wins over the .cljc source when newer"
    (let [dir (temp-dir)
          sym (symbol (str "magic.test.tmp.paired" (gensym)))]
      (try
        (write-ns! dir sym ".cljc" (str "(ns " sym ")(def w :cljc)"))
        (write-unloadable-dll! dir sym ".cljc")
        (is (thrown? Exception (load-in dir sym)))
        (finally (Directory/Delete dir true))))))

;; the list is duplicated because build.clj compiles magic.api against the
;; previous clojure.core, so magic.api cannot read this one's copy
(deftest compile-and-load-agree-on-extension-precedence
  (testing "nos build compiles the file require would load"
    (is (= @#'api/source-extensions
           @#'clojure.core/source-extensions))))

(deftest emitted-assembly-names-are-the-names-load-probes
  (let [dir   (temp-dir)
        sym   'magic.test.tmp.probe
        names (map #(str (api/assembly-name (str sym) %) ".dll")
                   [".cljr" ".cljc" ".clj"])]
    (try
      (testing "each source extension gets its own assembly name"
        (is (= 3 (count (distinct names)))))
      (testing "and -load searches for exactly those"
        (let [msg (try (load-in dir sym) nil
                       (catch Exception e (root-message e)))]
          (is (string? msg))
          (doseq [n names]
            (is (string/includes? msg n)))))
      (finally (Directory/Delete dir true)))))

(deftest reader-conditionals-only-in-cljc
  (doseq [ext [".cljr" ".clj"]]
    (testing (str ext " names one platform, so a reader conditional is an error")
      (let [dir (temp-dir)
            sym (symbol (str "magic.test.tmp.cond" (gensym)))]
        (try
          (write-ns! dir sym ext
                     (str "(ns " sym ")(def v #?(:cljr :a :clj :b))"))
          (let [msg (try (load-in dir sym) nil
                         (catch Exception e (root-message e)))]
            (is (string? msg))
            (is (string/includes? msg "Conditional read not allowed")))
          (finally (Directory/Delete dir true))))))
  (testing "a .cljc file honours reader conditionals"
    (let [dir (temp-dir)
          sym (symbol (str "magic.test.tmp.condok" (gensym)))]
      (try
        (write-ns! dir sym ".cljc"
                   (str "(ns " sym ")(def v #?(:cljr :a :clj :b))"))
        (load-in dir sym)
        (is (= :a @(ns-resolve (find-ns sym) 'v)))
        (finally (Directory/Delete dir true))))))

(deftest missing-namespace-names-what-was-searched
  (let [dir (temp-dir)]
    (try
      (let [msg (try (load-in dir 'magic.test.tmp.absent) nil
                     (catch Exception e (root-message e)))]
        (is (string? msg))
        (is (string/includes? msg "Could not locate"))
        (testing "every candidate extension is listed"
          (doseq [candidate ["magic/test/tmp/absent.cljr"
                             "magic/test/tmp/absent.clj"
                             "magic/test/tmp/absent.cljc"
                             "magic.test.tmp.absent.clj.dll"
                             "magic.test.tmp.absent.cljc.dll"
                             "magic.test.tmp.absent.cljr.dll"]]
            (is (string/includes? msg candidate))))
        (testing "the embedded-resource source no longer masks the report"
          (is (not (string/includes? msg "embedded resources")))))
      (finally (Directory/Delete dir true)))))

(deftest reader-failure-does-not-truncate-silently
  (testing "an unreadable form raises instead of ending the compilation unit"
    (let [dir (temp-dir)
          sym (symbol (str "magic.test.tmp.badtag" (gensym)))]
      (try
        (write-ns! dir sym ".clj"
                   (str "(ns " sym ")(def x #unknown/tag 1)(def after :present)"))
        (let [msg (try (load-in dir sym) nil
                       (catch Exception e (root-message e)))]
          (is (string? msg))
          (is (string/includes? msg "unknown/tag")))
        (finally (Directory/Delete dir true))))))

(deftest top-level-nil-does-not-end-the-unit
  (testing "forms after a top-level nil still compile"
    (let [dir (temp-dir)
          sym (symbol (str "magic.test.tmp.nilmid" (gensym)))]
      (try
        (write-ns! dir sym ".clj"
                   (str "(ns " sym ")(def a 1)\nnil\n(def b 2)"))
        (load-in dir sym)
        (is (= 1 @(ns-resolve (find-ns sym) 'a)))
        (is (= 2 @(ns-resolve (find-ns sym) 'b)))
        (finally (Directory/Delete dir true))))))
