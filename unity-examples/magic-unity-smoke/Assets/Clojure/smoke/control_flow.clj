(ns smoke.control-flow
  "Control flow and core data: loop/recur, try/catch/finally,
  lazy-seq, basic numerics. Broad coverage of compiler emit paths
  at low cost.")

(defn- pass [n]       {:name n :pass? true})
(defn- fail [n detail] {:name n :pass? false :detail detail})

(defn- check [name thunk expected]
  (try
    (let [actual (thunk)]
      (if (= expected actual)
        (pass name)
        (fail name (str "expected " (pr-str expected) " got " (pr-str actual)))))
    (catch System.Exception e
      (fail name (str (.. e GetType FullName) ": " (.Message e))))))

(defn suite []
  [(check "loop/recur sum 0..99"
          #(loop [n 0 acc 0]
             (if (= n 100) acc (recur (inc n) (+ acc n))))
          4950)
   (check "recur in fn tail"
          #((fn f [n acc] (if (zero? n) acc (recur (dec n) (* acc 2)))) 10 1)
          1024)
   (check "try/catch swallow"
          #(try (throw (System.Exception. "boom"))
                (catch System.Exception _ :caught))
          :caught)
   (check "try/finally side-effect"
          #(let [a (atom 0)]
             (try (try (throw (System.Exception. "boom"))
                       (finally (swap! a inc)))
                  (catch System.Exception _ @a)))
          1)
   (check "lazy-seq take"
          #(reduce + (take 10 (iterate inc 1)))
          55)
   (check "lazy-seq doall realisation"
          #(count (doall (map inc (range 1000))))
          1000)
   (check "numeric promotion"
          #(+ 1 (* 2 3) (/ 10 2))
          12)
   (check "bigint overflow safety"
          #(*' 1000000 1000000 1000000)
          1000000000000000000N)
   (check "boolean coercion"
          #(if 0 :truthy :falsy)
          :truthy)
   (check "destructuring in let"
          #(let [{:keys [a b] :or {b 99}} {:a 1}]
             [a b])
          [1 99])
   (check "named fn self-reference is the fn value"
          #(let [f (fn me [_k v] (if (nil? v) me v))]
             [(identical? f (f :a nil)) (= f (f :a nil)) (f :a 7)])
          [true true 7])
   (check "fn value carries no reader meta"
          #(meta (fn [] 1))
          nil)
   (check "if where both branches throw"
          #(try ((fn [c] (if (= c -1)
                           (throw (System.Exception. "a"))
                           (throw (System.Exception. "b")))) 0)
                (catch System.Exception e (.Message e)))
          "b")
   (check "cond where every branch throws"
          #(try ((fn [x] (cond (= x 1) (throw (System.Exception. "one"))
                               :else   (throw (System.Exception. "other")))) 9)
                (catch System.Exception e (.Message e)))
          "other")
   (check "inst and uuid literal constants"
          #(let [d #inst "2007-10-16T00:00:00.000-00:00"
                 u #uuid "3b8a31ed-fd89-4f1b-a00f-42e3d60cf5ce"]
             [(.Year d)
              (= u #uuid "3b8a31ed-fd89-4f1b-a00f-42e3d60cf5ce")
              (class d)])
          [2007 true System.DateTime])
   (check "cast boxed value to narrow numeric types"
          #(let [c (rand-nth [65])
                 u (rand-nth [4294967295])]
             [(char c) (long (uint u))])
          [\A 4294967295])
   (check "unsigned integer arithmetic promotes to long"
          #(inc UInt32/MaxValue)
          4294967296)
   (check "signed integer arithmetic promotes to long"
          #(inc Int32/MaxValue)
          2147483648)])
