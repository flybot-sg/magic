(ns magic.api
  (:refer-clojure :exclude [compile load-file eval])
  (:require [magic.analyzer :as ana]
            [magic.analyzer.types :as types :refer [tag ast-type type-lookup-cache-clear!]]
            [magic.core :as magic]
            [magic.util :as u]
            [mage.core :as il]
            magic.intrinsics
            ;; loaded so magic.core/active-spells can find-var the default spells
            magic.spells.lift-vars
            magic.spells.lift-keywords
            [magic.emission :refer [*module* fresh-module]]
            [clojure.string :as string])
  (:import [clojure.lang RT LineNumberingTextReader]
           [System.IO Path Directory File]
           [System.Reflection MethodAttributes TypeAttributes]
           [System.Reflection.Emit AssemblyBuilder ModuleBuilder]
           [System.Security.Cryptography SHA256]))

(def empty-args (into-array []))
(def public-static (enum-or MethodAttributes/Public MethodAttributes/Static))
(def abstract-sealed (enum-or TypeAttributes/Public TypeAttributes/Abstract TypeAttributes/Sealed))

(defn trim
  [x l]
  (let [s (str x)]
    (if (< (count s) l)
      s
      (subs s 0 l))))

(def read-options
  {:read-cond :allow
   :features #{:cljr}})

;; not shared with clojure.core's copy: build.clj compiles this namespace
;; before clojure.core, against the previous one
(def ^:private source-extensions [".cljr" ".cljc" ".clj"])

(defn compile-expression [expr ctx opts]
  (when-not (:suppress-print-forms opts)
    (println "[compile-expression]" (-> expr (trim 30)) (str *ns*) (ns-aliases *ns*)))
  (let [ast (ana/analyze expr)
        il (magic/compile ast)
        ;; named after the file's unit, not *ns*: an ns form's (in-ns ...)
        ;; compiles while *ns* is still the caller's
        expr-name (u/gensym (str "<magic>_" (or (::unit opts) (-> *ns* str munge)) "_expr"))
        expr-type (.DefineType magic.emission/*module* expr-name abstract-sealed)
        expr-method (if (:compiled-for-eval opts)
                      (.DefineMethod expr-type "eval" public-static
                                     ;; a form that only throws or recurs has no type
                                     (if (types/disregard-type? ast) System.Void (ast-type ast))
                                     Type/EmptyTypes)
                      (.DefineMethod expr-type "eval" public-static))
        expr-ilg (.GetILGenerator expr-method)
        ctx' (assoc ctx
                    ::il/type-builder expr-type
                    ::il/method-builder expr-method
                    ::il/ilg expr-ilg)]
    (il/emit! ctx'
              [il
               (when-not (:compiled-for-eval opts)
                 (il/pop))
               (il/ret)])
    (when-not (:compiled-for-eval opts)
      (il/emit! ctx (il/call expr-method)))
    (.CreateType expr-type)
    (.Invoke (.GetMethod expr-type "eval") nil empty-args)))

(defn- macroexpand-top-level
  "Expands macro calls, not inline functions, until the form stops changing."
  [form]
  (if (and (seq? form)
           (symbol? (first form))
           (-> (first form) resolve meta :macro))
    (let [form' (ana/macroexpand-1 form)]
      (if (= form' form)
        form
        (recur form')))
    form))

(defn compile-expression-top-level [expr ctx opts]
  (when-not (:suppress-print-forms opts)
    (println "[compile-expression-top-level]" expr))
  (let [expr (macroexpand-top-level expr)]
    (if (and (seq? expr)
             (= 'do (first expr)))
      (reduce (fn [_ expr'] (compile-expression-top-level expr' ctx opts))
              nil
              (rest expr))
      (compile-expression expr ctx opts))))

(defn clojure-clr-init-class-name
  "Port of clojure.lang.Compiler/InitClassName"
  [path]
  (str "__Init__$" (-> path (string/replace "." "/") (string/replace "/" "$"))))

;; AssemblyBuilder.Save stamps a random MVID and the current time, the only
;; non-source-derived bytes once emission order is deterministic. Patch them
;; (offsets parsed from the PE / CLI metadata layout).

(defn- b->i32 [bytes off]
  (BitConverter/ToInt32 bytes (int off)))

(defn- b->u16 [bytes off]
  (int (BitConverter/ToUInt16 bytes (int off))))

(defn- read-ascii-cstr
  "Null-terminated ASCII string in bytes starting at off."
  [bytes off]
  (loop [i (int off) acc []]
    (let [c (int (aget bytes i))]
      (if (zero? c)
        (apply str (map char acc))
        (recur (inc i) (conj acc c))))))

(defn- rva->file-offset
  "Translate a PE relative virtual address to a file offset via the section table."
  [bytes pe-off rva]
  (let [num-sections (b->u16 bytes (+ pe-off 6))
        opt-size     (b->u16 bytes (+ pe-off 20))
        sections-off (+ pe-off 24 opt-size)]
    (loop [i 0]
      (if (< i num-sections)
        (let [sec     (+ sections-off (* i 40))
              vsize   (b->i32 bytes (+ sec 8))
              va      (b->i32 bytes (+ sec 12))
              raw-ptr (b->i32 bytes (+ sec 20))]
          (if (and (>= rva va) (< rva (+ va vsize)))
            (+ (- rva va) raw-ptr)
            (recur (inc i))))
        (throw (Exception. (str "RVA " rva " not found in any PE section")))))))

(defn- guid-heap-offset
  "File offset of the #GUID metadata heap, which holds the module MVID."
  [bytes]
  (let [pe-off       (b->i32 bytes 0x3C)
        opt-off      (+ pe-off 24)
        pe32+?       (= 0x20B (b->u16 bytes opt-off))
        data-dir-off (+ opt-off (if pe32+? 112 96))
        cli-off      (rva->file-offset bytes pe-off (b->i32 bytes (+ data-dir-off (* 14 8))))
        meta-off     (rva->file-offset bytes pe-off (b->i32 bytes (+ cli-off 8)))
        version-len  (b->i32 bytes (+ meta-off 12))
        version-pad  (* 4 (quot (+ version-len 3) 4))
        streams-off  (+ meta-off 16 version-pad)
        num-streams  (b->u16 bytes (+ streams-off 2))]
    (loop [i 0 hdr (+ streams-off 4)]
      (if (< i num-streams)
        (let [stream-off (b->i32 bytes hdr)
              name       (read-ascii-cstr bytes (+ hdr 8))
              name-pad   (* 4 (quot (+ (inc (count name)) 3) 4))]
          (if (= name "#GUID")
            (+ meta-off stream-off)
            (recur (inc i) (+ hdr 8 name-pad))))
        (throw (Exception. "#GUID metadata heap not found in assembly"))))))

(defn normalize-assembly!
  "Rewrite a freshly-saved assembly so identical source yields identical bytes."
  [path]
  (let [bytes    (File/ReadAllBytes path)
        ts-off   (int (+ (b->i32 bytes 0x3C) 8))
        guid-off (int (guid-heap-offset bytes))]
    (System.Array/Clear bytes ts-off (int 4))
    (System.Array/Clear bytes guid-off (int 16))
    (let [sha  (SHA256/Create)
          hash (.ComputeHash sha bytes)]
      (.Dispose sha)
      (System.Array/Copy hash (int 0) bytes guid-off (int 16))
      ;; stamp UUID version (4) and RFC-4122 variant bits for a well-formed GUID
      (aset bytes (int (+ guid-off 7))
            (unchecked-byte (bit-or (bit-and (int (aget hash 7)) 0x0F) 0x40)))
      (aset bytes (int (+ guid-off 8))
            (unchecked-byte (bit-or (bit-and (int (aget hash 8)) 0x3F) 0x80))))
    (File/WriteAllBytes path bytes)))

(def ^:private rt-id-field
  (.GetField clojure.lang.RT "_id"
             (enum-or System.Reflection.BindingFlags/NonPublic
                      System.Reflection.BindingFlags/Static)))

(defn- reset-global-gensym-counter!
  "Emitted auto-gensym names must depend on the compilation unit alone, not
   on what the process consumed before it. Resetting mid-process weakens
   gensym uniqueness for the host."
  []
  (.SetValue rt-id-field nil (int 10000)))

(defn assembly-name
  "Module name for a compilation unit; the extension stays in it because -load
   probes for <ns><ext>.dll."
  [module extension]
  (-> module
      str
      (string/replace "/" ".")
      (str extension)))

(defn- analysis-error
  "A ClojureException naming the innermost analyzer error, and caused by it."
  [e]
  (loop [e e
         data (ex-data e)
         source-span (-> data :meta :source-span)
         file (:file data)]
    (if-let [e' (:exception data)]
      (recur e' (ex-data e') (or (-> (ex-data e') :meta :source-span) source-span) (or (:file (ex-data e') file)))
      (clojure.lang.ClojureException.
       (str (.Message e) (:name data) " (compiling " file ":" (:start-line source-span) ":" (:start-column source-span) ")")
       e))))

(defn- unit-error
  "e, thrown by the form at line and column of the unit at path."
  [path line column e]
  (cond
    ;; already located, e.g. by a unit this one requires
    (instance? clojure.lang.Compiler+CompilerException e)
    e

    (and (instance? System.Reflection.TargetInvocationException e)
         (instance? clojure.lang.Compiler+CompilerException (.InnerException e)))
    (.InnerException e)

    :else
    (clojure.lang.Compiler+CompilerException.
     path (int line) (int column) nil
     ;; a form runs through MethodInfo.Invoke, which wraps what it throws, so
     ;; anything unwrapped failed before running
     (if (instance? System.Reflection.TargetInvocationException e)
       clojure.lang.Compiler+CompilerException/PhaseExecutionKeyword
       clojure.lang.Compiler+CompilerException/PhaseCompileSyntaxCheckKeyword)
     (if (instance? clojure.lang.ExceptionInfo e) (analysis-error e) e))))

(defn compile-file
  ([path module]
   (compile-file path module nil))
  ([path module opts]
   (when-not (:suppress-print-forms opts)
     (println "[compile-file] start" path))
   (let [extension   (Path/GetExtension path)
         module-name (assembly-name module extension)
         ;; *file* is load-relative like the JVM: an absolute path bakes the
         ;; checkout directory into the emitted bytes via :file var metadata
         source-path (str (string/replace (str module) "." "/")
                          extension)]
     (binding [*ns*                    *ns*
               *file*                  source-path
               *unchecked-math*        *unchecked-math*
               *warn-on-reflection*    *warn-on-reflection*
               *data-readers*          *data-readers*
               magic.emission/*module* (magic.emission/fresh-module module-name)
               ;; a fresh type-name counter per unit, not a reset of the shared
               ;; one: a require in the ns form compiles a dependency mid-unit,
               ;; and a reset would restart this unit's names
               u/*gensym-map*          (atom {})]
       (let [type-name        (clojure-clr-init-class-name module)
             ns-type          (.DefineType magic.emission/*module* type-name abstract-sealed)
             init-method      (.DefineMethod ns-type "Initialize" public-static)
             init-ilg         (.GetILGenerator init-method)
             ctx              {::il/module-builder magic.emission/*module*
                               ::il/type-builder   ns-type
                               ::il/method-builder init-method
                               ::il/ilg            init-ilg}
             file             (System.IO.File/OpenText path)
             module-file-name (str module-name ".dll")]
         (reset-global-gensym-counter!)
         (type-lookup-cache-clear!)
         ;; evals get a module of the unit's own: the counters reset above
         ;; would repeat type names already in the enclosing load's module
         (with-bindings {clojure.lang.Compiler/CompilerContextVar (volatile! nil)}
           (try
             (let [rdr    (LineNumberingTextReader. file)
                   ;; an :eof sentinel keeps a genuine reader failure from looking
                   ;; like end of input, which would truncate the unit silently
                   read-opts (cond-> (assoc read-options :eof ::eof)
                               (not (.EndsWith (str path) ".cljc")) (dissoc :read-cond))
                   read-1 (fn []
                            (try
                              (read read-opts rdr)
                              (catch clojure.lang.LispReader+ReaderException e
                                (throw (clojure.lang.Compiler+CompilerException.
                                        path (.Line e) (.Column e) nil
                                        clojure.lang.Compiler+CompilerException/PhaseReadKeyword
                                        (.InnerException e))))))]
               (loop []
                 (let [line   (.LineNumber rdr)
                       column (.ColumnNumber rdr)
                       expr   (read-1)]
                   (when-not (= ::eof expr)
                     (try
                       (compile-expression-top-level expr ctx (assoc opts ::unit (str module)))
                       (catch Exception e
                         ;; the form's position, as upstream's Compile1 takes it
                         (let [{:keys [line column] :or {line line column column}} (meta expr)]
                           (throw (unit-error path line column e)))))
                     (recur))))
               (.Close rdr))
             (finally
               (.Close file))))
         (il/emit! ctx (il/ret))
         (.CreateType ns-type)
         (let [compile-path (or *compile-path* ".")
               file-name    module-file-name
               final-path   (Path/Combine compile-path file-name)]
           (Directory/CreateDirectory compile-path)
           (if-not (File/Exists final-path)
             (let [assembly (.Assembly magic.emission/*module*)]
               (Magic.Emission/EmitAssembly assembly file-name)
               (normalize-assembly! file-name)
               (when-not (= compile-path ".")
                 (File/Move file-name final-path)))
             (println "[compile-file] file already exists" final-path))
           (when-not (:suppress-print-forms opts)
             (println "[compile-file] end" path "->" final-path))))))))

(defn compile-namespace
  "Writes namespace's DLL to *compile-path*, or the current directory.

  Keys in opts:
  :suppress-print-forms - if logical true, suppresses printing each compiled form"
  ([namespace]
   (compile-namespace namespace nil))
  ([namespace opts]
   (when-not (:suppress-print-forms opts)
     (println "[compile-namespace]" namespace))
   (let [relative-path (-> namespace str munge (.Replace "." "/"))]
     (if-let [file (some #(find-file (str relative-path %)) source-extensions)]
       (compile-file (.FullName file) (munge (str namespace)) opts)
       (throw (Exception. (str "could not find source file for namespace " namespace)))))))

(defn compiler-munge [s]
  (-> s (.Replace "/" ".") (.Replace "-" "_")))

(defn present-error [e]
  (throw (analysis-error e)))

(defn- eval-module
  "The enclosing `load`'s module, made on first use; a fresh one outside a load."
  []
  (if-let [^clojure.lang.Volatile unit (.deref clojure.lang.Compiler/CompilerContextVar)]
    (let [thread         System.Threading.Thread/CurrentThread
          ;; only the thread that made the module gets it: emitting into one module
          ;; from two threads fails
          [owner module] (locking unit
                           (or @unit
                               (do (type-lookup-cache-clear!) ; should clear, otherwise it grows
                                   (vreset! unit [thread (fresh-module "load")]))))]
      (if (identical? owner thread)
        module
        (fresh-module "eval")))
    (fresh-module "eval")))

(defn eval [expr]
  (try
    (binding [*module* (eval-module)]
      (let [ctx {::il/module-builder *module*}]
        (compile-expression-top-level
         expr ctx
         {:compiled-for-eval true
          :suppress-print-forms true})))
    (catch clojure.lang.ExceptionInfo e
      (present-error e))))

(defn runtime-compile-file [file path]
  (compile-file (.FullName file) (compiler-munge (str path))
                {:suppress-print-forms true}))

(defn runtime-macroexpand-1 [form]
  (ana/macroexpand-1 form))

(def backend
  "The compiler clojure.core calls through clojure.lang.Compiler/Backend."
  (reify clojure.lang.ICompilerBackend
    (Eval [_ form] (eval form))
    (Macroexpand1 [_ form] (runtime-macroexpand-1 form))
    (CompileFile [_ file path] (runtime-compile-file file path))))

(def version "0.0-alpha")
