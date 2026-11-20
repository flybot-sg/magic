(ns magic.deps.magic-edn
  "A project's magic.edn: the options and compiler flags of its profiles.")

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
  (let [build     (merge {:out "build" :clean? true} (:build config))
        test-opts (merge {:aliases [:test]} (:test config))]
    (case profile
      :build (assoc build :flags (merge production-flags (:flags build)))
      :test  (assoc test-opts :flags (merge production-flags redef-flags (:flags test-opts)))
      (throw (ex-info (str "Unknown magic.edn profile " (pr-str profile)
                           ", expected :build or :test")
                      {:profile profile})))))

(defn resolve-flags
  "`flags` keyed by var instead of symbol, for `with-bindings`."
  [flags]
  (reduce-kv (fn [m sym v]
               (require (symbol (namespace sym)))
               (assoc m (resolve sym) v))
             {}
             flags))
