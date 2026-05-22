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
          [1 99])])
