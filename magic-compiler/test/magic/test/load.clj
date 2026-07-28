(ns magic.test.load
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as string])
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

(defn- load-in [dir sym]
  (binding [*load-paths* [dir]]
    (-load (-> (str sym) (.Replace "-" "_") (.Replace "." "/")))))

(defn- root-message [^Exception e]
  (if-let [inner (.InnerException e)]
    (recur inner)
    (.Message e)))

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
