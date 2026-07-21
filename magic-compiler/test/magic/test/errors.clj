(ns magic.test.errors
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as string]
            [magic.analyzer.util :as util]
            [magic.analyzer.errors :as errors])
  (:import [System.IO Directory File Path]))

(deftest unloaded-dll-hint
  (let [dir (Path/Combine (Path/GetTempPath) (str "magic-test-dll-hint-" (gensym)))
        original (Environment/GetEnvironmentVariable "CLOJURE_LOAD_PATH")]
    (Directory/CreateDirectory dir)
    (File/WriteAllText (Path/Combine dir "my_interop.dll") "")
    (try
      (Environment/SetEnvironmentVariable "CLOJURE_LOAD_PATH" dir)
      (testing "matching DLL on the load path produces a hint naming it"
        (let [hint (util/unloaded-dll-hint 'my_interop.Parser)]
          (is (string? hint))
          (is (string/includes? hint "my_interop.dll"))))
      (testing "nested type names fall back to shorter prefixes"
        (is (string? (util/unloaded-dll-hint 'my_interop.Deep.Nested.Type))))
      (testing "no matching DLL stays silent"
        (is (nil? (util/unloaded-dll-hint 'absent_lib.Type))))
      (testing "unqualified names stay silent"
        (is (nil? (util/unloaded-dll-hint 'Parser))))
      (testing "missing-type error carries the hint"
        (let [msg (try
                    (errors/error ::errors/missing-type {:type 'my_interop.Parser})
                    (catch Exception e (.Message e)))]
          (is (string/includes? msg "my_interop.dll"))))
      (finally
        (Environment/SetEnvironmentVariable "CLOJURE_LOAD_PATH" original)
        (Directory/Delete dir true)))))
