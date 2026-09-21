(ns magic.test.loop-widening
  "Widening a loop binding is only fully exercised by the file-writing compile
  path: a TypeBuilder the pass declares and abandons surfaces at
  AssemblyBuilder.Save, which loading a namespace never reaches."
  (:require [clojure.test :refer [deftest is testing]]
            [magic.api :as api]
            [magic.flags :as flags])
  (:import [System.IO Directory File Path]))

(defn- temp-dir
  "A fresh directory per call. gensym restarts at the same number in every
  process, so a name built from it collides with an earlier run's directory,
  and compile-file skips a DLL that is already on disk."
  []
  (let [dir (Path/Combine (Path/GetTempPath)
                          (str "magic-test-loop-widening-" (System.Guid/NewGuid)))]
    (Directory/CreateDirectory dir)
    dir))

(defn- compile-to-dll!
  "Write source as sym into a fresh directory, compile it to a DLL there, and
  load the DLL back. Deletes the source first so the loader cannot fall back
  to it. Returns the namespace symbol.

  The suite runs with direct linking and strongly typed invokes off, and the
  IL these loops emit lands in invokeTyped, so compile under the flags a
  shipped project builds with."
  [sym source]
  (let [dir      (temp-dir)
        relative (-> (str sym) (.Replace "-" "_") (.Replace "." "/"))
        path     (Path/Combine dir (str relative ".clj"))]
    (Directory/CreateDirectory (Path/GetDirectoryName path))
    (File/WriteAllText path source)
    (binding [*load-paths*                   [dir]
              *compile-path*                 dir
              flags/*direct-linking*         true
              flags/*strongly-typed-invokes* true]
      (api/compile-namespace sym {:write-files true :suppress-print-forms true})
      (File/Delete path)
      (-load relative))
    sym))


(defn- call [sym fn-name & args]
  (apply (resolve (symbol (str sym) (str fn-name))) args))

(deftest widened-init-declaring-a-type
  (testing "a fn, for, lazy-seq or reify init compiles when recur widens past IFn"
    (let [sym (symbol (str "magic.test.tmp.widen" (gensym)))]
      (compile-to-dll!
       sym
       (str "(ns " sym ")\n"
            "(defn a-fn [xs] (loop [q (fn [] xs) n 0] (if (zero? n) (recur nil (inc n)) q)))\n"
            "(defn a-for [xs] (loop [q (for [x xs] x) n 0] (if (zero? n) (recur nil (inc n)) q)))\n"
            "(defn a-lazy [xs] (loop [q (lazy-seq xs) n 0] (if (zero? n) (recur nil (inc n)) q)))\n"
            "(defn a-reify [xs] (loop [q (reify clojure.lang.IFn (invoke [this] xs)) n 0]\n"
            "                     (if (zero? n) (recur nil (inc n)) q)))\n"
            "(defn two-fns [xs b] (loop [q (if b (fn [] xs) (fn [] nil)) n 0]\n"
            "                       (if (zero? n) (recur nil (inc n)) q)))\n"))
      (is (nil? (call sym 'a-fn [1 2 3])))
      (is (nil? (call sym 'a-for [1 2 3])))
      (is (nil? (call sym 'a-lazy [1 2 3])))
      (is (nil? (call sym 'a-reify [1 2 3])))
      (is (nil? (call sym 'two-fns [1 2 3] true)))))

  (testing "a recur value that stays callable keeps the binding at IFn"
    (let [sym (symbol (str "magic.test.tmp.narrow" (gensym)))]
      (compile-to-dll!
       sym
       (str "(ns " sym ")\n"
            "(defn same [xs] (loop [q (fn [] xs) n 0] (if (zero? n) (recur q (inc n)) (q))))\n"
            "(defn kw [xs] (loop [q (fn [] xs) n 0] (if (zero? n) (recur :a (inc n)) q)))\n"))
      (is (= [1 2 3] (call sym 'same [1 2 3])))
      (is (= :a (call sym 'kw [1 2 3]))))))

(deftest init-reading-a-widened-binding
  (testing "a later init reading an earlier widened binding emits for the new type"
    (let [sym (symbol (str "magic.test.tmp.stale" (gensym)))]
      (compile-to-dll!
       sym
       (str "(ns " sym ")\n"
            "(defn arith [] (loop [a 1 b (inc a) n 0] (if (pos? n) (recur nil nil (inc n)) [b a])))\n"
            "(defn closure [] (loop [a 1 b (fn [] a) n 0] (if (pos? n) (recur nil nil (inc n)) [(b) a])))\n"
            "(defn closure-kept [] (loop [a 1 b (fn [] a) n 0] (if (pos? n) (recur nil b (inc n)) [(b) a])))\n"
            "(defn boxed [] (loop [a 1 b (str a) n 0] (if (pos? n) (recur nil nil (inc n)) [b a])))\n"))
      (is (= [2 1] (call sym 'arith)))
      (is (= [1 1] (call sym 'closure)))
      (is (= [1 1] (call sym 'closure-kept)))
      (is (= ["1" 1] (call sym 'boxed)))))

  (testing "bindings that read nothing, and a fn in the body, are unaffected"
    (let [sym (symbol (str "magic.test.tmp.independent" (gensym)))]
      (compile-to-dll!
       sym
       (str "(ns " sym ")\n"
            "(defn alone [] (loop [a 1 n 0] (if (pos? n) (recur nil (inc n)) a)))\n"
            "(defn two [] (loop [a 1 b 2 n 0] (if (pos? n) (recur nil nil (inc n)) [a b])))\n"
            "(defn body-fn [] (loop [a 1 n 0] (if (pos? n) (recur nil (inc n)) ((fn [] a)))))\n"
            "(defn no-widen [] (loop [a 1 b (inc a) n 0] (if (pos? n) (recur 2 nil (inc n)) [b a])))\n"))
      (is (= 1 (call sym 'alone)))
      (is (= [1 2] (call sym 'two)))
      (is (= 1 (call sym 'body-fn)))
      (is (= [2 1] (call sym 'no-widen))))))

(deftest generated-types-in-widening-loops
  (testing "every shape that declares a type survives a widening loop"
    (let [sym (symbol (str "magic.test.tmp.shapes" (gensym)))]
      (compile-to-dll!
       sym
       (str "(ns " sym ")\n"
            ;; the init and the body both declare a type, drawing from one pool
            "(defn pool-shuffle [] (loop [q (fn [] 1) n 0]\n"
            "  (if (zero? n) (recur nil (inc n)) [(if q 1 0) ((fn [] 7))])))\n"
            ;; RestFn base, so a different shape to match against
            "(defn variadic-init [] (loop [q (fn [& xs] (count xs)) n 0]\n"
            "  (if (zero? n) (recur nil (inc n)) (nil? q))))\n"
            ;; the inner init closes over the outer binding, which widens
            "(defn nested [] (loop [a 1 n 0]\n"
            "  (if (pos? n)\n"
            "    (loop [b (fn [] a) m 0] (if (pos? m) (recur nil (inc m)) [(b) a]))\n"
            "    (recur nil (inc n)))))\n"
            "(defn in-reify [] ((reify clojure.lang.IFn\n"
            "  (invoke [this] (loop [q (fn [] 1) n 0] (if (zero? n) (recur nil (inc n)) q))))))\n"
            "(defn letfn-init [] (loop [q (letfn [(h [] 1)] h) n 0]\n"
            "  (if (zero? n) (recur nil (inc n)) q)))\n"
            "(defn in-try [] (try (loop [q (fn [] 1) n 0]\n"
            "  (if (zero? n) (recur nil (inc n)) q)) (catch Exception _ :err)))\n"
            "(defn two-recurs [] (loop [q (fn [] 1) n 0]\n"
            "  (cond (zero? n) (recur nil (inc n)) (= n 1) (recur \"s\" (inc n)) :else q)))\n"
            "(defn for-reads-earlier [] (loop [a 1 b (for [x [1 2]] (+ x a)) n 0]\n"
            "  (if (pos? n) (recur nil nil (inc n)) [(vec b) a])))\n"
            "(defn chained [] (loop [a 1 b (inc a) c (inc b) n 0]\n"
            "  (if (pos? n) (recur nil nil nil (inc n)) [c b a])))\n"))
      (is (= [0 7] (call sym 'pool-shuffle)))
      (is (true? (call sym 'variadic-init)))
      (is (= [nil nil] (call sym 'nested)))
      (is (nil? (call sym 'in-reify)))
      (is (nil? (call sym 'letfn-init)))
      (is (nil? (call sym 'in-try)))
      (is (= "s" (call sym 'two-recurs)))
      (is (= [[2 3] 1] (call sym 'for-reads-earlier)))
      (is (= [3 2 1] (call sym 'chained))))))
