(ns pipeline
  "Render a Clojure form: form -> macroexpand -> AST -> symbolic IL."
  (:require [magic.analyzer :as ana]
            [magic.core :as magic]
            [magic.emission :as emission]
            [clojure.pprint :as pp]
            [clojure.walk :as walk]
            [clojure.string :as str]))

(def ^:private ast-noise-keys
  "Analyzer bookkeeping that hides AST shape: env, source-position,
   pre-expansion history, structural children. Stripped by `ast-skeleton`."
  #{:env :raw-forms :children :top-level :containing-fn-name
    :throws? :outside-type? :constant? :loop-id :meta
    :line :column :file :end-line :end-column :source-span})

(defn- ast-skeleton
  "Strip analyzer-bookkeeping keys from every AST node in `ast`."
  [ast]
  (walk/postwalk
   (fn [x]
     (if (and (map? x) (:op x))
       (reduce dissoc x ast-noise-keys)
       x))
   ast))

(defn- mage-instruction? [x]
  (and (map? x) (contains? x :mage.core/opcode)))

(defn- flatten-il
  "Mage instruction maps from `il`, in emission order. Walks only into
   sequential collections so instruction-map contents stay opaque."
  [il]
  (->> (tree-seq sequential? seq il)
       (filter mage-instruction?)
       vec))

(defn- render-method-info
  "Readable signature for a `MethodInfo` or `ConstructorInfo`,
   e.g. \"Numbers/isZero(Int64) -> Boolean\"."
  [m]
  (let [params (->> (.GetParameters m)
                    (map #(.Name (.ParameterType %)))
                    (str/join ","))
        ret    (when (instance? System.Reflection.MethodInfo m)
                 (.Name (.ReturnType m)))]
    (str (.Name (.DeclaringType m)) "/" (.Name m)
         "(" params ")" (when ret (str " -> " ret)))))

(defn- render-argument [argument]
  (cond
    (nil? argument) ""

    (instance? System.Reflection.MethodBase argument)
    (render-method-info argument)

    (map? argument)
    (let [{:mage.core/keys [local field label type]} argument]
      (cond
        local (str local " :type " type)
        field (str field " :type " type)
        label (str label)
        :else (pr-str argument)))

    :else (pr-str argument)))

(defn- render-instruction
  "Single-line pseudo-MSIL string for one mage instruction map."
  [{:mage.core/keys [opcode argument]}]
  (let [op-str (if (instance? clojure.lang.Named opcode)
                 (name opcode)
                 (str opcode))]
    (str/trim (str op-str " " (render-argument argument)))))

(def ^:private bar (apply str (repeat 64 "=")))

(defn- header [s]
  (println (str "\n" bar "\n" s "\n" bar)))

(defn- ensure-dir [dir]
  (System.IO.Directory/CreateDirectory dir)
  dir)

(defn- edn-safe
  "Coerce non-EDN-printable leaves (`#object[...]`, `#'ns/name`) to
   strings so the output is round-trippable by any EDN reader.
   System.Type gets `.FullName` instead of the default wrapping."
  [v]
  (walk/postwalk
   (fn [x]
     (cond
       (coll? x) x
       (instance? System.Type x) (.FullName x)
       :else (let [s (pr-str x)]
               (if (or (str/starts-with? s "#object[")
                       (str/starts-with? s "#'"))
                 (str x)
                 x))))
   v))

(defn- pp-str [v]
  (binding [*print-length* nil *print-level* nil]
    (with-out-str (pp/pprint (edn-safe v)))))

(defn- spit-edn [path v]
  (System.IO.File/WriteAllText path (pp-str v)))

(defn- spit-edn-lines
  "Write each item of `xs` to `path` on its own pprinted EDN chunk."
  [path xs]
  (->> xs (map pp-str) str/join (System.IO.File/WriteAllText path)))

(defn- typed-node? [x]
  (and (map? x) (:op x) (or (:type x) (:const-type x))))

(defn- type-entry
  "Project a typed AST node into a {:op :form :type} summary. Prefers
   `:const-type` (System.Type) over `:type` (analyzer's coarse tag)."
  [{:keys [op form type const-type]}]
  {:op op :form form :type (or const-type type)})

(defn- ast-nodes
  "All AST nodes reachable from `ast` (any map with `:op`)."
  [ast]
  (->> (tree-seq coll? seq ast)
       (filter #(and (map? %) (:op %)))))

(defn- collect-types
  "Distinct {:op :form :type} entries for every typed AST node."
  [ast]
  (->> (ast-nodes ast)
       (filter typed-node?)
       (map type-entry)
       distinct))

(defn- render-type [t]
  (if (instance? System.Type t) (.FullName t) t))

(defn- render-type-entry [{:keys [op form type]}]
  (str op " " (pr-str form) " :: " (render-type type)))

(defn- print-form [form]
  (header (str "FORM   " (pr-str form))))

(defn- print-macroexpand [form]
  (let [expanded (macroexpand form)]
    (when-not (= form expanded)
      (header "MACROEXPAND")
      (pp/pprint expanded))))

(defn- print-ast-skeleton [ast]
  (header "AST (skeleton)")
  (pp/pprint (ast-skeleton ast)))

(defn- print-types [ast]
  (when-let [entries (seq (collect-types ast))]
    (header (str "TYPES (" (count entries) " typed nodes)"))
    (run! #(println " " (render-type-entry %)) entries)))

(defn- print-il [insts]
  (header (str "SYMBOLIC IL (" (count insts) " instructions)"))
  (run! #(println " " (render-instruction %)) insts))

(defn- combine [dir file]
  (System.IO.Path/Combine dir file))

(defn- edn-paths
  "Filesystem path per requested `*-edn` section. Keys mirror the
   section names so the destructure stays symmetric at the call site."
  [out sections]
  (cond-> {}
    (:ast-edn sections)  (assoc :ast-edn  (combine out "pipeline-ast.edn"))
    (:il-edn sections)   (assoc :il-edn   (combine out "pipeline-il.edn"))
    (:tree-edn sections) (assoc :tree-edn (combine out "pipeline-il-tree.edn"))))

(defn- print-dump-paths [{:keys [ast-edn il-edn tree-edn]}]
  (header "EDN dumps")
  (when ast-edn  (println " " ast-edn  " ; full AST, pprinted"))
  (when il-edn   (println " " il-edn   " ; flat instruction list, pprinted (usually what you want)"))
  (when tree-edn (println " " tree-edn " ; nested IL tree as mage emits it (rarely needed)")))

(defn- analyze+compile
  "Module-bound setup `magic.api/eval` uses, so forms that synthesize
   CLR types (fn, deftype, defrecord) work without writing an assembly."
  [form]
  (binding [emission/*module* (emission/fresh-module "pipeline")]
    (let [ast (ana/analyze form)]
      {:ast ast :il (magic/compile ast)})))

(defn- show-pipeline
  ([form] (show-pipeline form {}))
  ([form {:keys [out sections]
          :or   {out      "target"
                 sections #{:form :macroexpand :ast :types :il
                            :ast-edn :il-edn :tree-edn}}}]
   (let [out                                         (str out)
         sections                                    (set sections)
         {:keys [ast-edn il-edn tree-edn] :as paths} (edn-paths out sections)]
     (when (seq paths) (ensure-dir out))
     (when (:form sections)        (print-form form))
     (when (:macroexpand sections) (print-macroexpand form))
     (let [{:keys [ast il]} (analyze+compile form)
           insts            (flatten-il il)]
       (when (:ast sections)   (print-ast-skeleton ast))
       (when (:types sections) (print-types ast))
       (when (:il sections)    (print-il insts))
       (when ast-edn  (spit-edn ast-edn ast))
       (when il-edn   (spit-edn-lines il-edn insts))
       (when tree-edn (spit-edn tree-edn il))
       (when (seq paths) (print-dump-paths paths))))))

(defn- coerce-form
  "nostrand pre-parses each task arg as a Clojure value, but if it
   chose to keep it as a String (single-quoted CLI input) we still
   need to read it."
  [x]
  (if (string? x) (read-string x) x))

(defn- parse-args
  "First arg is the form. Remaining args are :key value option pairs
   (already pre-parsed by nostrand into keywords / values)."
  [args]
  (let [[raw-form & opt-tokens] args]
    (when (odd? (count opt-tokens))
      (throw (ex-info "options must come as :key value pairs" {:tokens opt-tokens})))
    [(coerce-form raw-form) (apply hash-map opt-tokens)]))

(defn show
  "Walk a Clojure form through the compiler pipeline.
   Usage: nos pipeline/show '<form>' [:key value]...
   Options:
     :out       output dir for EDN dumps (default \"target\")
     :sections  subset of #{:form :macroexpand :ast :types :il
                            :ast-edn :il-edn :tree-edn} (default all)"
  [& args]
  (when-not (seq args)
    (throw (ex-info "usage: nos pipeline/show '<form>' [:key value]..." {})))
  (let [[form opts] (parse-args args)]
    (show-pipeline form opts)))
