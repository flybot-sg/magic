(ns magic.test.stdlib
  (:require [clojure.test :refer [deftest testing]]
            [clojure.main :as main]
            [clojure.core.server :as server])
  (:use magic.test.common))

;;; symbol (1.10): arity-1 works on strings, keywords, and vars

(deftest test-symbol
  (cljclr=magic
   (symbol 'already-a-symbol)
   (symbol "plain-string")
   (symbol :kw)
   (symbol :ns/kw)
   (symbol #'clojure.core/map)))

(deftest test-symbol-no-conversion
  ;; m/eval invokes the compiled form reflectively, wrapping throws in
  ;; TargetInvocationException, so assert on the directly-invoked fn instead.
  (clojure.test/is (thrown? ArgumentException (symbol 1))))

;;; read+string (1.10)

(defn- lntr [^String s]
  (clojure.lang.LineNumberingTextReader. (System.IO.StringReader. s)))

(deftest test-read+string
  ;; two reads from one reader also prove capture resets between calls
  (let [rdr (lntr "(+ 1 2) foo")]
    (clojure.test/is (= ['(+ 1 2) "(+ 1 2)"] (read+string rdr false nil)))
    (clojure.test/is (= ['foo "foo"] (read+string rdr false nil))))
  (testing "opts arity (read-cond)"
    (let [rdr (lntr "42")]
      (clojure.test/is (= [42 "42"] (read+string {:eof nil} rdr))))))

;;; PrintWriter-on (1.10)

(deftest test-printwriter-on
  (let [acc    (atom [])
        closed (atom false)
        w      (PrintWriter-on (fn [s] (swap! acc conj s))
                               (fn [] (reset! closed true)))]
    (.Write w "hello ")
    (.Write w "world")
    (clojure.test/is (= [] @acc) "nothing emitted before flush")
    (.Flush w)
    (clojure.test/is (= ["hello world"] @acc))
    (.Write w "more")
    (.Close w)
    (clojure.test/is (= ["hello world" "more"] @acc) "close flushes the buffer")
    (clojure.test/is (true? @closed) "close-fn called"))
  (testing "nil close-fn is allowed"
    (let [acc (atom [])
          w   (PrintWriter-on (fn [s] (swap! acc conj s)) nil)]
      (.Write w "x")
      (.Close w)
      (clojure.test/is (= ["x"] @acc)))))

;;; tap> / add-tap / remove-tap (1.10)

(deftest test-tap
  (let [seen (atom [])
        f (fn [x] (swap! seen conj x))]
    (clojure.test/is (nil? (add-tap f)))
    (clojure.test/is (true? (tap> 42)))
    (clojure.test/is (true? (tap> nil)))
    (System.Threading.Thread/Sleep 500)
    (clojure.test/is (nil? (remove-tap f)))
    (clojure.test/is (= [42 nil] @seen))
    (testing "removed tap no longer receives values"
      (reset! seen [])
      (tap> :after-remove)
      (System.Threading.Thread/Sleep 200)
      (clojure.test/is (= [] @seen)))))

;;; Throwable->map (1.10 shape): conditional :message/:cause, :phase key,
;;; declaring-type class symbol. Stack traces differ from ClojureCLR, so we
;;; assert explicit values on the stable keys instead of cljclr=magic.

(deftest test-throwable->map-shape
  ;; phase-present chain: one assertion over all the stable keys at once
  (let [inner (ex-info "inner boom" {:a 1})
        outer (ex-info "outer boom" {:clojure.error/phase :execution} inner)
        m     (Throwable->map outer)]
    (clojure.test/is
     (= {:cause     "inner boom"                       ; root-cause message
         :phase     :execution                         ; from the top ex-data
         :data      {:a 1}                              ; root-cause ex-data
         :types     ['clojure.lang.ExceptionInfo 'clojure.lang.ExceptionInfo]
         :messages  ["outer boom" "inner boom"]         ; outermost-first
         :datas     [{:clojure.error/phase :execution} {:a 1}]}
        {:cause    (:cause m)
         :phase    (:phase m)
         :data     (:data m)
         :types    (mapv :type (:via m))
         :messages (mapv :message (:via m))
         :datas    (mapv :data (:via m))}))))

(deftest test-throwable->map-no-phase
  ;; the :phase when-let nil branch: key is omitted, not nil
  (clojure.test/is
   (false? (contains? (Throwable->map (ex-info "plain" {:k :v})) :phase))))

(deftest test-throwable->map-frame-declaring-type
  ;; stack frames carry the method's declaring type, never the StackFrame type;
  ;; the exception must be thrown for the CLR to populate its stack trace
  (let [ex    (try (throw (ex-info "e" {})) (catch System.Exception e e))
        frame (first (:trace (Throwable->map ex)))]
    (clojure.test/is
     (and (= 4 (count frame))
          (symbol? (first frame))
          (not= 'System.Diagnostics.StackFrame (first frame))))))

;;; ex-triage / ex-str (1.10). err->msg and :clojure.error/path are post-1.10
;;; and intentionally not ported. These mirror clojure.test-clojure.main from
;;; ClojureCLR: an unthrown exception already has an empty stack trace, which
;;; makes the whole triage deterministic (no flaky :symbol/:source/:line).

(deftest test-null-stack-error-reporting
  (let [e       (ArgumentException. "xyz")
        tr-data (main/ex-triage (Throwable->map e))]
    (clojure.test/is
     (= {:clojure.error/phase :execution
         :clojure.error/class 'System.ArgumentException
         :clojure.error/cause "xyz"}
        tr-data))
    (clojure.test/is
     (= "Execution error (ArgumentException) at (REPL:1).\nxyz\n"
        (main/ex-str tr-data)))))

(deftest test-java-loc->source
  (clojure.test/are [c m out]
    (= out (#'main/java-loc->source c m))
    'user$eval1                'invokeStatic 'user/eval1
    'div$go                    'invokeStatic 'div/go
    'user$eval186$fn__187      'invoke       'user/eval186$fn
    'user$ok_fn$broken_fn__164 'invoke       'user/ok-fn$broken-fn
    'clojure.lang.Numbers      'divide       'clojure.lang.Numbers/divide))

;;; prepl / io-prepl (1.10). Neither Clojure nor ClojureCLR unit-tests these,
;;; so we drive prepl in-process over a string reader and assert the out-fn
;;; messages. prepl calls eval, so it runs only under Mono/nostrand, never
;;; under IL2CPP AOT; hence no smoke regression. remote-prepl is pure socket
;;; forwarding (a live socket pair) and is not unit-tested here.

(deftest test-prepl-eval
  (let [out (atom [])]
    (server/prepl (lntr "(+ 1 2)") #(swap! out conj %))
    (clojure.test/is
     (= [{:tag :ret :val 3 :ns "user" :form "(+ 1 2)"}]
        (mapv #(dissoc % :ms) @out)))))

(deftest test-prepl-exception
  ;; any eval failure is reported as one error-shaped :ret carrying ex->data
  (let [out (atom [])]
    (server/prepl (lntr "(/ 1 0)") #(swap! out conj %))
    (let [{:keys [tag exception val]} (first @out)]
      (clojure.test/is
       (and (= :ret tag)
            (true? exception)
            (= :execution (:phase val))
            (string? (:cause val)))))))

(deftest test-io-prepl
  ;; default valf is pr-str, so :ret :val 3 is printed as the string "3"
  (let [sw (System.IO.StringWriter.)]
    (binding [*in* (lntr "(+ 1 2)") *out* sw]
      (server/io-prepl))
    (clojure.test/is (.Contains (str sw) ":val \"3\""))))
