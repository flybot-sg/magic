(ns smoke.stdlib-1-10
  "Clojure 1.10 stdlib additions: symbol var/keyword conversion,
  read+string, and the tap> system. The tap suite exercises a
  BlockingCollection`1 worker thread driven by a gen-delegate
  ThreadStart, which is the part most likely to break under IL2CPP AOT.")

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

(def ^:private a-var 42)

(defn- lntr [s]
  (clojure.lang.LineNumberingTextReader. (System.IO.StringReader. s)))

(defn suite []
  [(check "symbol from keyword"
          #(symbol :foo)
          'foo)
   (check "symbol from qualified keyword"
          #(symbol :ns/foo)
          'ns/foo)
   (check "symbol from var is qualified"
          #(symbol #'smoke.stdlib-1-10/a-var)
          'smoke.stdlib-1-10/a-var)
   (check "read+string returns form and trimmed text"
          #(read+string (lntr "(+ 1 2) rest") false nil)
          ['(+ 1 2) "(+ 1 2)"])
   (check "read+string resets capture each call"
          #(let [r (lntr ":a :b")]
             (read+string r false nil)
             (read+string r false nil))
          [:b ":b"])
   (check "PrintWriter-on flush and close"
          #(let [acc    (atom [])
                 closed (atom false)
                 w      (PrintWriter-on (fn [s] (swap! acc conj s))
                                        (fn [] (reset! closed true)))]
             (.Write w "ab")
             (.Write w "c")
             (.Flush w)
             (.Write w "d")
             (.Close w)
             [@acc @closed])
          [["abc" "d"] true])
   (check "tap> round trip with nil sentinel"
          #(let [seen (atom [])
                 f    (fn [x] (swap! seen conj x))]
             (add-tap f)
             (tap> :tapped)
             (tap> nil)
             (System.Threading.Thread/Sleep 500)
             (remove-tap f)
             @seen)
          [:tapped nil])])
