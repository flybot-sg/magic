(ns refresh
  "Recompile every committed stdlib clojure.*.clj.dll from its source file and
   redeploy to nostrand/references/, nostrand/bin/Release/net471/, and
   magic-unity/Runtime/Infrastructure/Export/. Use after editing any
   magic-compiler/src/stdlib/**/*.clj.

   Invoke with: nos refresh/stdlib  (or `bb refresh-stdlib`)

   Why this exists: clojure.core/load-one picks between .clj source and .clj.dll
   by mtime comparison. git checkout sets arbitrary mtimes. If the DLL on disk
   does not contain a source fix, the runtime may silently use the stale DLL
   and the fix has no effect. This task ensures both stay in lockstep.
   Compilation is deterministic, so `bb check-drift` byte-diffs the redeployed
   DLLs against HEAD: a stale DLL shows up as a byte difference, whatever
   caused it (the stdlib source, the compiler, or the C# runtime)."
  (:require [magic.api :as api]
            [clojure.string :as str])
  (:import [System.IO File Path Directory FileInfo StreamReader]))

(def ^:private refs "../nostrand/references")
(def ^:private bin "../nostrand/bin/Release/net471")
(def ^:private unity "../magic-unity/Runtime/Infrastructure/Export")
(def ^:private stdlib-root "src/stdlib")

(def ^:private bootstrap-namespaces
  "Namespaces this task must not recompile: clojure.core plus the eight units
   build.clj compiles with it. Recompiling core.clj re-executes its top-level
   forms here, rebinding *load-paths* so nothing compiled afterwards resolves
   its source. The six `(in-ns 'clojure.core)` sub-files and the two namespaces
   core.clj pulls in are that same unit, and recompiling one of them re-emits
   clojure.core, which this task cannot write."
  '#{clojure.core
     clojure.core-clr
     clojure.core-proxy
     clojure.core-print
     clojure.core-deftype
     clojure.clr.io
     clojure.gvec
     clojure.genclass
     clojure.core.protocols})

(defn- top-level-ns?
  "True if the file's first non-comment, non-blank line starts with `(ns`.
   Sub-files included via `(load ...)` from a parent start with `(in-ns ...)`
   and compile standalone only after their parent has loaded (compiling the
   parent interns every var the sub-file's forms reference)."
  [^FileInfo src-file]
  (with-open [r (StreamReader. (.FullName src-file))]
    (loop []
      (let [line (.ReadLine r)]
        (cond
          (nil? line)                       false
          (re-find #"^\s*(?:;.*)?$" line)   (recur)
          (re-find #"^\s*\(ns(\s|$)" line)  true
          :else                             false)))))

(defn- dll->ns-symbol
  "clojure.pprint.pretty_writer.clj.dll -> clojure.pprint.pretty-writer (symbol)."
  [^String dll-filename]
  (-> dll-filename
      (str/replace #"\.clj\.dll$" "")
      (str/replace \_ \-)
      symbol))

(defn- ns-symbol->source-file
  "clojure.pprint.pretty-writer -> magic-compiler/src/stdlib/clojure/pprint/pretty_writer.clj
   (returns FileInfo or nil if not found)."
  [ns-sym]
  (let [rel (-> (str ns-sym)
                (str/replace \- \_)
                (str/replace \. (System.IO.Path/DirectorySeparatorChar)))
        p   (Path/Combine stdlib-root (str rel ".clj"))]
    (when (File/Exists p) (FileInfo. p))))

(defn- compile-namespaces!
  "Compile each namespace into tmp-dir, printing progress. Returns a vector of
   [ns message] for the ones that threw, empty when all of them compiled."
  [namespaces tmp-dir]
  (binding [clojure.core/*eval-form-fn*       api/eval
            clojure.core/*compile-file-fn*    api/runtime-compile-file
            clojure.core/*load-file-fn*       api/runtime-load-file
            clojure.core/*warn-on-reflection* true
            clojure.core/*compile-path*       tmp-dir
            clojure.core/*compile-files*      true]
    (reduce (fn [failed ns]
              (print (str "compiling " ns " ... "))
              (flush)
              (try
                (api/compile-namespace ns {:write-files true :suppress-print-forms true})
                (println "ok")
                failed
                (catch Exception e
                  (println "FAILED:" (.Message e))
                  (conj failed [ns (.Message e)]))))
            []
            namespaces)))

(defn stdlib [& _args]
  ;; ordinal sort: compile order feeds the gensym stream, and the default
  ;; culture-sensitive string compare orders differently across OS collations
  (let [dll-files     (->> (Directory/EnumerateFiles refs "clojure.*.clj.dll")
                           (map #(Path/GetFileName ^String %))
                           (sort (fn [^String a ^String b] (String/CompareOrdinal a b)))
                           vec)
        all-nss       (mapv dll->ns-symbol dll-files)
        ns->src       (into {} (for [ns all-nss
                                     :let [f (ns-symbol->source-file ns)]
                                     :when f]
                                 [ns f]))
        bootstrap-nss (filter bootstrap-namespaces all-nss)
        missing-src   (->> all-nss
                           (remove bootstrap-namespaces)
                           (remove ns->src))
        sourced-nss   (->> all-nss
                           (remove bootstrap-namespaces)
                           (filter ns->src))
        top-level-nss (vec (filter #(top-level-ns? (ns->src %)) sourced-nss))
        ;; (in-ns ...) sub-files, compiled after every top-level ns so their
        ;; parent's compile has interned the vars they reference. The mtime
        ;; rules keep the parent's own (load ...) from re-emitting them, so
        ;; without this explicit pass they would never be recompiled.
        subfile-nss   (vec (remove (set top-level-nss) sourced-nss))
        tmp-dir       (Path/GetFullPath "target/refresh-stdlib")]
    (println (str "found " (count all-nss) " deployed stdlib DLLs, "
                  (count top-level-nss) " top-level, "
                  (count subfile-nss) " sub-files, "
                  (count bootstrap-nss) " bootstrap-owned (bb bootstrap), "
                  (count missing-src) " without source (skipped)"))
    (when (seq missing-src)
      (doseq [ns missing-src] (println "  missing source for" ns)))

    (when (Directory/Exists tmp-dir) (Directory/Delete tmp-dir true))
    (Directory/CreateDirectory tmp-dir)

    (let [to-compile (concat top-level-nss subfile-nss)]
      ;; a half-refreshed set of committed DLLs is what this task exists to prevent
      (when-let [failures (seq (compile-namespaces! to-compile tmp-dir))]
        (println (str (count failures) " of " (count to-compile)
                      " namespaces failed to compile, so nothing was deployed"
                      " and the committed DLLs are untouched:"))
        (doseq [[ns message] failures]
          (println (str "  " ns " - " message)))
        (throw (ex-info "refresh/stdlib did not compile every namespace"
                        {:failed (mapv first failures)}))))

    (let [produced (->> (Directory/EnumerateFiles tmp-dir "clojure.*.clj.dll")
                        (map #(Path/GetFileName ^String %))
                        sort
                        vec)]
      (println (str "compiled " (count produced) " DLLs to " tmp-dir))
      (doseq [f produced
              :let [src (Path/Combine tmp-dir f)]]
        (File/Copy src (Path/Combine refs f) true)
        (File/Copy src (Path/Combine bin f) true)
        (File/Copy src (Path/Combine unity f) true))
      (Directory/Delete tmp-dir true))
    (println "done.")))
