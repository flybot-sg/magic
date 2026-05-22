(ns smoke.runner
  "Aggregates every smoke suite and exposes two vars that the
  SmokeTestRunner MonoBehaviour reads:

    `all-pass?`   () -> boolean
    `report-text` () -> string  (multi-line, ready to display)"
  (:require [smoke.value-types  :as value-types]
            [smoke.letfn-cases  :as letfn-cases]
            [smoke.polymorphism :as polymorphism]
            [smoke.control-flow :as control-flow]
            [clojure.string :as str]))

(defn- run []
  (let [groups [["value-types"  (value-types/suite)]
                ["letfn-cases"  (letfn-cases/suite)]
                ["polymorphism" (polymorphism/suite)]
                ["control-flow" (control-flow/suite)]]
        flat   (for [[group results] groups
                     r results]
                 (assoc r :group group))]
    {:results flat
     :total   (count flat)
     :passed  (count (filter :pass? flat))
     :failed  (remove :pass? flat)}))

(defn all-pass? []
  (empty? (:failed (run))))

(defn report-text []
  (let [{:keys [results total passed failed]} (run)
        header (str "MAGIC IL2CPP SMOKE: " passed "/" total " passed"
                    (when (seq failed) (str ", " (count failed) " failed")))
        fail-lines (for [{:keys [group name detail]} failed]
                     (str "FAIL  [" group "] " name "  --  " detail))
        pass-lines (for [{:keys [group name]} (filter :pass? results)]
                     (str "ok    [" group "] " name))]
    (str/join "\n"
              (concat [header ""]
                      fail-lines
                      (when (seq fail-lines) [""])
                      pass-lines))))
