(ns refresh
  "Recompile every committed stdlib clojure.*.clj.dll from its source file and
   redeploy to nostrand/references/, nostrand/bin/Release/net471/, and
   magic-unity/Runtime/Infrastructure/Export/. Also updates the source-SHA
   manifest used by `bb check-drift`. Use after editing any
   magic-compiler/src/stdlib/**/*.clj.

   Invoke with: nos refresh/stdlib  (or `bb refresh-stdlib`)

   Why this exists: clojure.core/load-one picks between .clj source and .clj.dll
   by mtime comparison. git checkout sets arbitrary mtimes. If the DLL on disk
   does not contain a source fix, the runtime may silently use the stale DLL
   and the fix has no effect. This task ensures both stay in lockstep, and the
   manifest gives CI a deterministic check that no source was edited without
   re-emitting its DLL (DLL bytes themselves are non-deterministic due to
   gensyms and internal hash tokens, so we cannot byte-diff them)."
  (:require [magic.api :as api]
            [clojure.string :as str])
  (:import [System.IO File Path Directory FileInfo StreamReader]
           [System.Security.Cryptography SHA256]))

(def ^:private refs "../nostrand/references")
(def ^:private bin "../nostrand/bin/Release/net471")
(def ^:private unity "../magic-unity/Runtime/Infrastructure/Export")
(def ^:private stdlib-root "src/stdlib")
(def ^:private manifest-path "stdlib-manifest.edn")

(defn- sha256-hex [^String path]
  (let [bytes (File/ReadAllBytes path)
        algo  (SHA256/Create)
        hash  (.ComputeHash algo bytes)]
    (.Dispose algo)
    (apply str (map #(format "%02x" %) hash))))

(def ^:private bootstrap-namespaces
  "Namespaces compiled by build.clj's bootstrap chain. We must NOT re-compile
   these here because their source re-defines runtime vars like *load-paths*
   that would break subsequent compiles in the same process. They are kept
   in sync via `bb build-magic` + `bb build-bootstrap`."
  '#{clojure.core
     clojure.string
     clojure.set
     clojure.walk
     clojure.clr.io
     clojure.gvec
     clojure.genclass
     clojure.core.protocols
     clojure.tools.analyzer
     clojure.tools.analyzer.ast
     clojure.tools.analyzer.env
     clojure.tools.analyzer.utils
     clojure.tools.analyzer.passes
     clojure.tools.analyzer.passes.cleanup
     clojure.tools.analyzer.passes.elide-meta
     clojure.tools.analyzer.passes.source-info
     clojure.tools.analyzer.passes.trim})

(def ^:private known-broken-namespaces
  "Namespaces whose source cannot currently be compiled from CLR Clojure.
   clojure.datafy uses `.FullName` on `clojure.lang.Namespace`, which is a
   JVM-only API (the CLR Namespace exposes `.Name`). The committed
   clojure.datafy.clj.dll appears to be hand-patched. Skipped here so the
   refresh task doesn't fail; the DLL retains its committed content.
   Fixing the source is a separate task."
  '#{clojure.datafy})

(defn- top-level-ns?
  "True if the file's first non-comment, non-blank line starts with `(ns`.
   Sub-files included via `(load ...)` from a parent start with `(in-ns ...)`
   and can not be compiled standalone."
  [^FileInfo src-file]
  (with-open [r (StreamReader. (.FullName src-file))]
    (loop []
      (let [line (.ReadLine r)]
        (cond
          (nil? line)                       false
          (re-find #"^\s*(?:;.*)?$" line)   (recur)
          (re-find #"^\s*\(ns\s" line)      true
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

(defn stdlib [& _args]
  (let [dll-files     (->> (Directory/EnumerateFiles refs "clojure.*.clj.dll")
                           (map #(Path/GetFileName ^String %))
                           sort
                           vec)
        all-nss       (mapv dll->ns-symbol dll-files)
        ns->src       (into {} (for [ns all-nss
                                     :let [f (ns-symbol->source-file ns)]
                                     :when f]
                                 [ns f]))
        missing-src   (remove ns->src all-nss)
        top-level-nss (->> all-nss
                           (remove bootstrap-namespaces)
                           (remove known-broken-namespaces)
                           (filter ns->src)
                           (filter #(top-level-ns? (ns->src %)))
                           vec)
        tmp-dir       (Path/GetFullPath "target/refresh-stdlib")]
    (println (str "found " (count all-nss) " deployed stdlib DLLs, "
                  (count top-level-nss) " top-level, "
                  (count missing-src) " without source (skipped)"))
    (when (seq missing-src)
      (doseq [ns missing-src] (println "  missing source for" ns)))

    (when (Directory/Exists tmp-dir) (Directory/Delete tmp-dir true))
    (Directory/CreateDirectory tmp-dir)

    (binding [clojure.core/*eval-form-fn*       api/eval
              clojure.core/*compile-file-fn*    api/runtime-compile-file
              clojure.core/*load-file-fn*       api/runtime-load-file
              clojure.core/*warn-on-reflection* true
              clojure.core/*compile-path*       tmp-dir
              clojure.core/*compile-files*      true]
      (doseq [ns top-level-nss]
        (print (str "compiling " ns " ... "))
        (flush)
        (try
          (api/compile-namespace ns {:write-files true :suppress-print-forms true})
          (println "ok")
          (catch Exception e
            (println "FAILED:" (.Message e))))))

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

    ;; Update manifest with source SHA for every refreshed namespace.
    ;; This is what `bb check-drift` compares against, since DLL bytes are
    ;; non-deterministic (gensyms, hash tokens) and cannot be byte-diffed.
    (let [cwd      (Path/GetFullPath ".")
          existing (try (read-string (slurp manifest-path)) (catch Exception _ {}))
          rel-src  (fn [^FileInfo fi]
                     (let [full (.FullName fi)]
                       (if (.StartsWith full cwd)
                         (.Substring full (inc (.Length cwd)))
                         full)))
          updated  (reduce (fn [m ns]
                             (let [fi (ns->src ns)]
                               (assoc m ns {:source (rel-src fi)
                                            :sha256 (sha256-hex (.FullName fi))})))
                           existing
                           top-level-nss)
          sorted   (into (sorted-map) updated)]
      (spit manifest-path
            (str ";; Auto-generated by `bb refresh-stdlib`. DO NOT EDIT BY HAND.\n"
                 ";; Maps each stdlib namespace to the SHA256 of the .clj source from which\n"
                 ";; its .clj.dll was last emitted. `bb check-drift` fails if any source has\n"
                 ";; been edited since (i.e., manifest sha256 != current source sha256).\n"
                 "{\n"
                 (str/join "\n" (for [[k v] sorted]
                                  (str " " (pr-str k) " " (pr-str v))))
                 "}\n"))
      (println "wrote manifest" manifest-path "with" (count sorted) "entries"))
    (println "done.")))
