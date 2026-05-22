(ns magic.test.pipeline
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [pipeline :as pipeline]
            [magic.analyzer :as ana]
            [magic.core :as magic]
            [mage.core :as il]))

(defn- opcodes
  "Compile `form`, flatten the resulting IL, and return each opcode
   stringified for easy assertion (mage opcodes are CLR OpCode value
   types, not symbols/keywords)."
  [form]
  (->> form
       ana/analyze
       magic/compile
       (#'pipeline/flatten-il)
       (map (comp str :mage.core/opcode))))

(deftest test-ast-skeleton
  (testing "drops noise keys from a single AST node"
    (is (= {:op :const :val 5 :type :number}
           (#'pipeline/ast-skeleton
            {:op :const :val 5 :type :number
             :children []
             :env {:context :ctx/expr}
             :raw-forms (list 5)
             :top-level true}))))
  (testing "postwalk recurses into nested AST nodes and cleans them too"
    (is (= {:op :let
            :body {:op :local :name 'x}}
           (#'pipeline/ast-skeleton
            {:op :let
             :children [:body]
             :env {:noise true}
             :body {:op :local :name 'x :children [] :env {:more :noise}}})))))

(deftest test-mage-instruction?
  (testing "true for a real mage instruction map"
    (is (#'pipeline/mage-instruction? (il/add))))
  (testing "false for a vector of instructions (the plausible mistake)"
    (is (not (#'pipeline/mage-instruction? [(il/add)])))))

(deftest test-flatten-il
  (testing "flattens deep, drops nils, preserves emission order"
    (let [a (il/ldc-i8 1)
          b (il/add)
          c (il/ret)]
      (is (= [a b c]
             (#'pipeline/flatten-il
              [[a nil [[b] c]] nil]))))))

(deftest test-edn-safe
  (testing "nested CLR Types are replaced everywhere they appear"
    (is (= {:outer {:type "System.Int64"}
            :siblings ["System.Double" "System.String"]}
           (#'pipeline/edn-safe
            {:outer {:type System.Int64}
             :siblings [System.Double System.String]}))))
  (testing "non-printable values are rendered to identifying strings"
    (let [out (#'pipeline/edn-safe {:v #'+})]
      (is (str/includes? (:v out) "clojure.core/+"))))
  (testing "output round-trips through read-string"
    (let [safe (#'pipeline/edn-safe {:type System.Int64 :n 5 :form 'sym})]
      (is (= safe (read-string (pr-str safe)))))))

(defn- has-op?
  "Match an opcode family by regex. Mage picks short-form variants
   (`stloc.0`, `stloc.s`) based on slot number, so exact-string matching
   would flap when the optimizer changes its mind."
  [ops pattern]
  (boolean (some #(re-find pattern %) ops)))

(deftest test-pipeline-let-with-local
  (testing "(let [x 5] x) emits ldc / stloc / ldloc"
    (let [ast (ana/analyze '(let [x 5] x))
          ops (opcodes '(let [x 5] x))]
      (is (= :let (:op ast)))
      (is (has-op? ops #"^ldc\.i") "integer literal load")
      (is (has-op? ops #"^stloc") "store local")
      (is (has-op? ops #"^ldloc") "load local"))))

(deftest test-pipeline-intrinsic-add
  (testing "(+ x 1) with Int64 x becomes :intrinsic with overflow-checked add"
    (let [ast (ana/analyze '(let [x 1] (+ x 1)))
          body (:body ast)
          ops (opcodes '(let [x 1] (+ x 1)))]
      (is (= :intrinsic (:op body)))
      (is (= System.Int64 (:type body)))
      (is (has-op? ops #"^add\.ovf"))
      (is (not (has-op? ops #"^call"))
          "intrinsic should not emit a call"))))

(deftest test-pipeline-double-promotion
  (testing "(+ x 1) with Double x promotes the integer literal to r8"
    (let [ast (ana/analyze '(let [x 1.0] (+ x 1)))
          body (:body ast)
          ops (opcodes '(let [x 1.0] (+ x 1)))]
      (is (= :intrinsic (:op body)))
      (is (= System.Double (:type body)))
      (is (has-op? ops #"^ldc\.r8") "literal 1 is reborn as 1.0")
      (is (has-op? ops #"^add$") "unchecked add for floats")
      (is (not (has-op? ops #"^add\.ovf"))
          "no overflow check on float arithmetic"))))

(deftest test-pipeline-static-method-resolved
  (testing "(Math/Abs x) with known Int64 x resolves to :static-method"
    (let [body (:body (ana/analyze '(let [x 1] (Math/Abs x))))]
      (is (= :static-method (:op body)))
      (is (some? (:method body))))))

(deftest test-pipeline-if-constant-folding
  (testing "(if true :z :nz) compiles only the :then branch"
    (let [ast (ana/analyze '(if true :z :nz))
          ops (opcodes '(if true :z :nz))]
      (is (= :if (:op ast)))
      (is (not (has-op? ops #"^br(true|false)"))
          "constant-true test means no conditional branch is emitted"))))

(defn- temp-dir
  "Per-test temp dir so the IO test doesn't depend on CWD or race with
   concurrent `bb pipeline` invocations writing to magic-compiler/target/."
  []
  (let [d (System.IO.Path/Combine
           (System.IO.Path/GetTempPath)
           (str "pipeline-test-" (System.Guid/NewGuid)))]
    (System.IO.Directory/CreateDirectory d)
    d))

(defn- file-in [dir name]
  (System.IO.Path/Combine dir name))

(deftest test-show-pipeline-writes-readable-edn
  (let [tmp (temp-dir)]
    (binding [*out* (System.IO.StringWriter.)]
      (#'pipeline/show-pipeline '(let [x 1] (+ x 1)) {:out tmp}))
    (testing "AST dump parses back via plain read-string"
      (let [ast (read-string (slurp (file-in tmp "pipeline-ast.edn")))]
        (is (= :let (:op ast)))
        (is (= :intrinsic (:op (:body ast))))))
    (testing "IL dump parses back and contains opcode maps"
      (let [il (read-string (slurp (file-in tmp "pipeline-il.edn")))
            flat (#'pipeline/flatten-il il)]
        (is (pos? (count flat)))))))

(deftest test-show-pipeline-sections-filters-dumps
  (testing ":sections #{:ast-edn} writes only the AST file"
    (let [tmp (temp-dir)]
      (binding [*out* (System.IO.StringWriter.)]
        (#'pipeline/show-pipeline '(let [x 1] x)
                                  {:out tmp :sections #{:ast-edn}}))
      (is      (System.IO.File/Exists (file-in tmp "pipeline-ast.edn")))
      (is (not (System.IO.File/Exists (file-in tmp "pipeline-il.edn"))))
      (is (not (System.IO.File/Exists (file-in tmp "pipeline-il-tree.edn"))))))
  (testing ":sections #{:il-edn :tree-edn} writes IL and tree, skips AST"
    (let [tmp (temp-dir)]
      (binding [*out* (System.IO.StringWriter.)]
        (#'pipeline/show-pipeline '(let [x 1] x)
                                  {:out tmp :sections #{:il-edn :tree-edn}}))
      (is (not (System.IO.File/Exists (file-in tmp "pipeline-ast.edn"))))
      (is      (System.IO.File/Exists (file-in tmp "pipeline-il.edn")))
      (is      (System.IO.File/Exists (file-in tmp "pipeline-il-tree.edn"))))))

(deftest test-show-pipeline-sections-filters-stdout
  (testing ":sections #{:il} prints only the SYMBOLIC IL banner"
    ;; `when-let` expands, so MACROEXPAND would normally print --
    ;; verifying it does NOT appear here actually tests the section filter.
    (let [out (with-out-str
                (#'pipeline/show-pipeline '(when-let [x 1] x)
                                          {:sections #{:il}}))]
      (is      (str/includes? out "SYMBOLIC IL"))
      (is (not (str/includes? out "FORM   ")))
      (is (not (str/includes? out "AST (skeleton)")))
      (is (not (str/includes? out "TYPES (")))
      (is (not (str/includes? out "MACROEXPAND")))
      (is (not (str/includes? out "EDN dumps"))))))

(deftest test-parse-args
  (testing "single form arg, no options"
    (let [[form opts] (#'pipeline/parse-args '((let [x 1] x)))]
      (is (= '(let [x 1] x) form))
      (is (= {} opts))))
  (testing "form plus :key value pairs"
    (let [[form opts] (#'pipeline/parse-args
                       '((let [x 1] x) :out tmp :sections #{:il}))]
      (is (= '(let [x 1] x) form))
      (is (= 'tmp (:out opts)))
      (is (= #{:il} (:sections opts)))))
  (testing "odd number of option tokens throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (#'pipeline/parse-args '((form) :out))))))

(deftest test-coerce-form
  (testing "string is read into a form"
    (is (= '(+ 1 2) (#'pipeline/coerce-form "(+ 1 2)"))))
  (testing "non-string value passes through unchanged"
    (is (= '(+ 1 2) (#'pipeline/coerce-form '(+ 1 2))))))
