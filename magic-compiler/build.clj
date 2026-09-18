(ns build
  (:require [magic.api :refer [compile-namespace]])
  (:import [System.IO File Directory Path DirectoryInfo]))

(in-ns 'clojure.core)

;; forward declare vars added to clojure.core after 1.9 to preserve
;; bootstrapping. ideally we would just compile clojure.core first but that
;; causes its own problems.
(defmacro -forward-declare-new-vars [vars]
  `(do
     ~@(map (fn [v] `(let [vv# (declare ~v)] (println "forward declaring" vv#))) vars)))

(-forward-declare-new-vars [requiring-resolve])

(in-ns 'build)

(def std-libs-to-compile
  '[clojure.tools.analyzer.env
    clojure.tools.analyzer.utils
    clojure.tools.analyzer
    clojure.string
    clojure.set
    clojure.tools.analyzer.ast
    magic.analyzer.binder
    magic.analyzer.util
    magic.analyzer.reflection
    magic.flags
    magic.emission
    magic.analyzer.types
    magic.analyzer.generated-types
    magic.analyzer.loop-bindings
    magic.analyzer.uniquify
    magic.interop
    mage.core
    magic.util
    magic.core
    magic.spells.lift-vars
    magic.spells.lift-keywords
    magic.analyzer.errors
    magic.analyzer.analyze-host-forms
    magic.analyzer.novel
    magic.analyzer.intrinsics
    magic.analyzer.typed-passes
    clojure.tools.analyzer.passes
    clojure.tools.analyzer.passes.source-info
    clojure.tools.analyzer.passes.elide-meta
    clojure.tools.analyzer.passes.trim
    clojure.tools.analyzer.passes.cleanup
    magic.analyzer.collect-closed-overs
    magic.analyzer.remove-local-children
    magic.analyzer.untyped-passes
    clojure.walk
    magic.analyzer
    magic.analyzer.literal-reinterpretation
    magic.intrinsics
    magic.api
    clojure.core-proxy
    clojure.core-print
    clojure.genclass
    clojure.core-deftype
    clojure.core.protocols
    clojure.gvec
    clojure.clr.io
    clojure.core-clr
    clojure.core]) ;; if clojure.core not at the end, prevent other files from being compiled

(defn- spell-flag-bindings
  "Map of spell flag var -> true for each requested spell (a keyword or a
  fully-qualified spell fn symbol, matched by name). Loads a symbol spell's
  namespace so magic.core/active-spells can find-var it, and resolves the flag
  var at runtime so this loads on a compiler that predates the flags. Extra
  spells are only used post-transition (e.g. the sparse-case bootstrap pass)."
  [spells]
  (into {} (keep (fn [s]
                   (when (symbol? s) (require (symbol (namespace s))))
                   (when-let [v (resolve (symbol "magic.flags" (str \* (name s) \*)))]
                     [v true]))
                 spells)))

(defn- stdlib-ns?
  "Whether a namespace's source lives under src/stdlib. Same rule bb's drift
   check uses to partition the committed DLLs; a namespace with no in-tree
   source (the vendored clojure.tools.analyzer.*) is not stdlib."
  [lib]
  (let [rel (-> (str lib) (.Replace "-" "_") (.Replace "." "/"))]
    (boolean (some #(File/Exists (str "src/stdlib/" rel %)) [".clj" ".cljc"]))))

(defn- compile-path-for
  "bootstrap/stdlib for a stdlib namespace, bootstrap/compiler for the rest.
   The two directories are the partition Magic.csproj deploys from."
  [lib]
  (if (stdlib-ns? lib) "bootstrap/stdlib" "bootstrap/compiler"))

(defn bootstrap [& {:keys [spells]}]
  (binding [clojure.core/*loaded-libs* (ref (sorted-set))
            *eval-form-fn*             magic.api/eval
            *compile-file-fn*          magic.api/runtime-compile-file
            *load-file-fn*             magic.api/runtime-load-file
            *warn-on-reflection*       true]
    (with-bindings* (spell-flag-bindings spells)
      (fn []
        ;; Order is load-bearing for bootstrapping; only the output directory
        ;; varies per namespace.
        (doseq [lib std-libs-to-compile]
          (println (str "building " lib))
          (binding [*compile-path* (compile-path-for lib)]
            (compile-namespace lib {:write-files true :suppress-print-forms true})))))))
