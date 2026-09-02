(ns magic.test.errors
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as string]
            [magic.api :as m]
            [magic.analyzer.util :as util]
            [magic.analyzer.errors :as errors])
  (:import [System InvalidOperationException]
           [System.IO Directory File Path]))

(defn- root-message [^Exception e]
  (if-let [inner (.InnerException e)]
    (recur inner)
    (.Message e)))

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
      (testing "deferred import failure carries the hint at runtime"
        (let [msg (try
                    (Magic.Runtime/FindTypeOrThrow "my_interop.Parser")
                    (catch InvalidOperationException e (.Message e)))]
          (is (string/includes? msg "Could not find type my_interop.Parser during import"))
          (is (string/includes? msg "my_interop.dll"))))
      (testing "compiled deferred import throws the hint when the type never loads"
        (let [msg (try
                    (m/eval '(clojure.core/import* "my_interop.Parser"))
                    (catch Exception e (root-message e)))]
          (is (string/includes? msg "Could not find type my_interop.Parser during import"))
          (is (string/includes? msg "my_interop.dll"))))
      (testing "longest matching prefix wins"
        (File/WriteAllText (Path/Combine dir "my_interop.Deep.dll") "")
        (let [msg (try
                    (Magic.Runtime/FindTypeOrThrow "my_interop.Deep.Nested.Type")
                    (catch InvalidOperationException e (.Message e)))]
          (is (string/includes? msg "my_interop.Deep.dll"))))
      (testing "no matching DLL yields the plain import error"
        (let [msg (try
                    (Magic.Runtime/FindTypeOrThrow "absent_lib.Type")
                    (catch InvalidOperationException e (.Message e)))]
          (is (string/includes? msg "Could not find type absent_lib.Type during import"))
          (is (not (string/includes? msg "found")))))
      (testing "unqualified names yield the plain import error"
        (let [msg (try
                    (Magic.Runtime/FindTypeOrThrow "AbsentType")
                    (catch InvalidOperationException e (.Message e)))]
          (is (not (string/includes? msg "found")))))
      (testing "path-hostile characters in the type name do not mask the import error"
        (is (thrown? InvalidOperationException
                     (Magic.Runtime/FindTypeOrThrow "my|lib<>*.Type"))))
      (testing "deferred import resolves loaded types"
        (is (= System.String (Magic.Runtime/FindTypeOrThrow "System.String")))
        (is (= clojure.lang.PersistentVector (Magic.Runtime/FindTypeOrThrow "clojure.lang.PersistentVector")))
        (is (= System.String (Magic.Runtime/FindTypeOrThrow (.AssemblyQualifiedName System.String)))))
      (finally
        (Environment/SetEnvironmentVariable "CLOJURE_LOAD_PATH" original)
        (Directory/Delete dir true)))))

(deftest find-type-or-throw-load-path-edge-cases
  (let [original (Environment/GetEnvironmentVariable "CLOJURE_LOAD_PATH")
        empty-dir (Path/Combine (Path/GetTempPath) (str "magic-test-empty-" (gensym)))
        dll-dir (Path/Combine (Path/GetTempPath) (str "magic-test-dll-" (gensym)))]
    (Directory/CreateDirectory empty-dir)
    (Directory/CreateDirectory dll-dir)
    (File/WriteAllText (Path/Combine dll-dir "my_interop.dll") "")
    (try
      (testing "the DLL is found in a later load path entry"
        (Environment/SetEnvironmentVariable
         "CLOJURE_LOAD_PATH" (str empty-dir Path/PathSeparator dll-dir))
        (let [msg (try
                    (Magic.Runtime/FindTypeOrThrow "my_interop.Parser")
                    (catch InvalidOperationException e (.Message e)))]
          (is (string/includes? msg (Path/Combine dll-dir "my_interop.dll")))))
      (testing "unset load path yields the plain import error"
        (Environment/SetEnvironmentVariable "CLOJURE_LOAD_PATH" nil)
        (let [msg (try
                    (Magic.Runtime/FindTypeOrThrow "my_interop.Parser")
                    (catch InvalidOperationException e (.Message e)))]
          (is (string/includes? msg "during import"))
          (is (not (string/includes? msg "found")))))
      (testing "blank and nonexistent load path entries are skipped"
        (Environment/SetEnvironmentVariable
         "CLOJURE_LOAD_PATH"
         (str Path/PathSeparator "/nonexistent-dir" Path/PathSeparator))
        (is (thrown? InvalidOperationException
                     (Magic.Runtime/FindTypeOrThrow "my_interop.Parser"))))
      (finally
        (Environment/SetEnvironmentVariable "CLOJURE_LOAD_PATH" original)
        (Directory/Delete empty-dir true)
        (Directory/Delete dll-dir true)))))

(deftest constant-without-print-dup
  (testing "unembeddable constant fails at compile time naming print-dup"
    (let [msg (try
                (str (m/eval (list 'fn [] (System.Random.))))
                (catch Exception e (root-message e)))]
      (is (string/includes? msg "print-dup")))))

;;; a non-IFn callee throws a catchable cast error rather than failing verification

(defn- root-type-name [^Exception e]
  (if-let [inner (.InnerException e)]
    (recur inner)
    (.Name (.GetType e))))

(defn- eval-outcome [form]
  (try (m/eval form) :no-throw
       (catch Exception e (root-type-name e))))

(deftest non-ifn-callee-throws-cast-error
  (testing "a value-typed callee is boxed, so the method verifies and the cast throws"
    (doseq [form ['(1 2)                       ; boxed
                  '(true 2)                    ; a const Boolean converts to Magic.Constants instead
                  '(let [x (int 1)] (x 2))]]   ; value-typed, and not a literal
      (is (= "InvalidCastException" (eval-outcome form)) (pr-str form))))
  (testing "a reference-typed callee already behaved"
    (is (= "InvalidCastException" (eval-outcome '("s" 2)))))
  (testing "an IFn callee still runs"
    (is (= 20 (m/eval '([10 20] 1))))))
