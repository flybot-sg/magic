(ns smoke.letfn-cases
  "letfn invocation and mutual recursion.

  Regression for develop 52a7a438: mutually recursive letfn-bound
  functions used to NullReferenceException because closure fields
  stayed null after instance allocation.")

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
  [(check "letfn simple"
          #(letfn [(twice [x] (* x 2))
                   (six-times [y] (* (twice y) 3))]
             [(twice 15) (six-times 15)])
          [30 90])
   (check "letfn mutual recursion"
          #(letfn [(even2  [n] (neven? n))
                   (neven? [n] (if (zero? n) true  (nodd?  (dec n))))
                   (nodd?  [n] (if (zero? n) false (neven? (dec n))))]
             [(even2 91) (even2 90)])
          [false true])
   (check "letfn closing over let-binding"
          #(let [k 10]
             (letfn [(add-k [x] (+ x k))]
               (add-k 5)))
          15)])
