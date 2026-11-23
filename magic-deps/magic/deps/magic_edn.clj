(ns magic.deps.magic-edn
  "A project's magic.edn: the options and compiler flags of its profiles."
  (:import [System.IO File Path])
  (:require [clojure.edn :as edn]))

(def production-flags
  "Symbol -> value map, as in magic.edn's :flags."
  '{clojure.core/*unchecked-math*        false
    clojure.core/*warn-on-reflection*    true
    magic.flags/*strongly-typed-invokes* true
    magic.flags/*direct-linking*         true
    magic.flags/*elide-meta*             false})

(def redef-flags
  "Off so callers see a redefined fn: both lower a call past the Var."
  '{magic.flags/*direct-linking*         false
    magic.flags/*strongly-typed-invokes* false})

(defn options
  "Returns a profile's options from config, defaults applied, and :flags
  merged over the base flags."
  [config profile]
  (let [build (-> (merge {:out "build" :clean? true} (:build config))
                  (update :flags #(merge production-flags %)))]
    (case profile
      :build build
      ;; :dev is the same as :build, but uses different :out and redef-flags
      :dev   (assoc build
                    :out    (get-in config [:dev :out] "build-dev")
                    ;; a compile never overwrites an existing DLL
                    :clean? true
                    :flags  (merge (:flags build) redef-flags))
      :test  (-> (merge {:aliases [:test]} (:test config))
                 (update :flags #(merge production-flags redef-flags %)))
      (throw (ex-info (str "Unknown magic.edn profile " (pr-str profile)
                           ", expected :build, :dev or :test")
                      {:profile profile})))))

(defn- flag-var
  [sym]
  (when-let [ns-name (namespace sym)]
    (try
      (require (symbol ns-name))
      (catch System.IO.FileNotFoundException _))
    (let [v (resolve sym)]
      (when (and (var? v) (.isDynamic ^clojure.lang.Var v))
        v))))

(defn resolve-flags
  "`flags` keyed by var instead of symbol."
  [flags]
  (let [vars    (into {} (map (juxt identity flag-var)) (keys flags))
        unknown (filterv (comp nil? vars) (keys flags))]
    (when (seq unknown)
      (throw (ex-info (str "Unknown or non-dynamic magic.edn flags " unknown)
                      {:unknown unknown})))
    (reduce-kv (fn [m sym v] (assoc m (vars sym) v)) {} flags)))

(defn set-flag-roots!
  "Set as roots, not bound, so compiles on every thread see them."
  [flags]
  (doseq [[v value] (resolve-flags flags)]
    (alter-var-root v (constantly value))))

(defn read-config
  [root]
  (let [file (Path/Combine (str root) "magic.edn")]
    (when (File/Exists file)
      (edn/read-string (slurp file)))))
