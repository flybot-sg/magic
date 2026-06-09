(ns smoke.value-types
  "Instance methods on value types.

  Regression for develop 40b4237b: zero-arity instance members on
  Int64, Double, and other value types used to throw
  InvalidProgramException under IL2CPP because MAGIC emitted plain
  callvirt instead of constrained.callvirt.")

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
  [(check "string .Length"      #(.Length "hello")              5)
   (check "long .GetType"       #(.GetType 90)                  System.Int64)
   (check "double .GetType"     #(.GetType 90.0)                System.Double)
   (check "string .GetType"     #(.GetType "hi")                System.String)
   (check "long .ToString"      #(.ToString 42)                 "42")
   (check "double .ToString"    #(.ToString 1.5)                "1.5")
   (check "long .Equals same"   #(.Equals 7 7)                  true)
   (check "long .Equals diff"   #(.Equals 7 8)                  false)
   (check "double .Equals same" #(.Equals 1.0 1.0)              true)])
