(ns smoke.interop
  "CLR interop whose codegen only an IL2CPP build fully exercises.

  by-ref on a type-hinted local: a ^-hinted local wraps in a :tagged
  node that the analyzer used to reject before codegen. The fix emits
  an address-load of the value-type local into an out-parameter call,
  which Mono JITs but only IL2CPP AOT-transpiles for real.")

(defn- pass [n]        {:name n :pass? true})
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
  [(check "Int64.TryParse by-ref local"
          #(let [r (long 0)]
             [(Int64/TryParse "42" (by-ref r)) r])
          [true 42])
   (check "Int64.TryParse by-ref ^long local"
          #(let [r (long 0)]
             [(Int64/TryParse "42" (by-ref ^long r)) r])
          [true 42])])
