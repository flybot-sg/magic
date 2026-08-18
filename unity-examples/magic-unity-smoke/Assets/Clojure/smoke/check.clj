(ns smoke.check
  "Shared verdict helper for the smoke suites: run a thunk, compare to the
  expected value, return the pass/fail map the runner aggregates.")

(defn- pass [n]        {:name n :pass? true})
(defn- fail [n detail] {:name n :pass? false :detail detail})

(defn check [name thunk expected]
  (try
    (let [actual (thunk)]
      (if (= expected actual)
        (pass name)
        (fail name (str "expected " (pr-str expected) " got " (pr-str actual)))))
    (catch System.Exception e
      (fail name (str (.. e GetType FullName) ": " (.Message e))))))
