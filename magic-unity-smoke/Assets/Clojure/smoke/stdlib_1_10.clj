(ns smoke.stdlib-1-10
  "Clojure 1.10 stdlib additions: symbol var/keyword conversion,
  read+string, PrintWriter-on, the tap> system, Throwable->map, and the
  ex-triage/ex-str error-triage pair. The tap suite exercises a
  BlockingCollection`1 worker thread driven by a gen-delegate ThreadStart,
  which is the part most likely to break under IL2CPP AOT. Throwable->map
  walks the InnerException chain and reads stack frames via
  System.Diagnostics.StackTrace. ex-triage/ex-str exercise the
  String.Join array overload and the Printf formatter under AOT."
  (:require [clojure.main :as cmain]))

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

(defprotocol SmokeViaMeta
  :extend-via-metadata true
  (smoke-via-meta [x]))

(extend-protocol SmokeViaMeta
  Object
  (smoke-via-meta [_] :extend-table))

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
          [:tapped nil])
   (check "Throwable->map cause/phase/via/data"
          #(let [inner (ex-info "inner" {:a 1})
                 outer (ex-info "outer" {:clojure.error/phase :exec} inner)
                 m     (Throwable->map outer)]
             [(:cause m)
              (:phase m)
              (:data m)
              (mapv :message (:via m))
              (:type (first (:via m)))])
          ["inner" :exec {:a 1} ["outer" "inner"] 'clojure.lang.ExceptionInfo])
   (check "ex-triage/ex-str over a thrown ex-info"
          #(let [ex     (try (throw (ex-info "boom" {})) (catch System.Exception e e))
                 triage (cmain/ex-triage (Throwable->map ex))
                 s      (.Replace (cmain/ex-str triage) "\r\n" "\n")]
             [(:clojure.error/phase triage)
              (:clojure.error/class triage)
              (:clojure.error/cause triage)
              (.StartsWith s "Execution error (ExceptionInfo) at")])
          [:execution 'clojure.lang.ExceptionInfo "boom" true])
   (check "extend-via-metadata dispatches to metadata impl"
          #(smoke-via-meta (with-meta {} {`smoke-via-meta (fn [_] :from-meta)}))
          :from-meta)
   (check "extend-via-metadata falls through to extend table"
          #(smoke-via-meta {})
          :extend-table)])
