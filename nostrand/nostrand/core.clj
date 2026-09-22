(ns
 ^{:author "Ramsey Nasser"
   :doc "Core nostrand API containing load path, assemblies, and dependency functions."}
 nostrand.core
  (:require [clojure.string :as string]
            [nostrand.deps.basis :as basis]
            [nostrand.deps.submodules :as submodules])
  (:import [System.IO Directory Path File]))

(defn- absolute-path
  "path as an absolute path with no trailing separator, resolved against the
  cwd of this call, so a later cwd change cannot move a root already added."
  [path]
  (when (string/blank? (str path))
    (throw (ex-info "Load path entry is blank" {:path path})))
  (let [full (Path/GetFullPath (str path))]
    (if (and (> (count full) 1)
             (string/ends-with? full (str Path/DirectorySeparatorChar)))
      (subs full 0 (dec (count full)))
      full)))

(defn- path-list
  "Absolute entries of a PathSeparator-joined list. Blank entries are dropped
  rather than rejected: they come from a stray separator, not a named path."
  [s]
  (into [] (comp (remove string/blank?) (map absolute-path))
        (string/split (or s "") (re-pattern (str Path/PathSeparator)))))

(def -assembly-path
  (atom (string/split (or (Environment/GetEnvironmentVariable "MONO_PATH") ".")
                      (re-pattern (str Path/PathSeparator)))))

(def -load-path
  (atom (path-list (Environment/GetEnvironmentVariable "CLOJURE_LOAD_PATH"))))

(defonce ^{:private true
           :doc "*load-paths* as the runtime left it, set when this namespace is
  first loaded, so a host must load nostrand.core before adding any project root."}
  -base-load-paths (vec *load-paths*))

(defn- load-path-roots []
  (into [] (distinct) @-load-path))

(defn resolve-assembly-load [asm]
  (let [candidates (for [prefix @-assembly-path
                         ext ["" ".dll" ".exe"]]
                     ;; asm contains more info than just the name itself.
                     (let [file-name (-> asm (string/split (re-pattern (str ","))) first)]
                       (Path/Combine prefix (str file-name ext))))
        full-asm-path (first (filter #(File/Exists %) candidates))]
    (when full-asm-path
      (assembly-load-from full-asm-path))))

(defn update-load-path []
  (let [roots (load-path-roots)]
    ;; CLOJURE_LOAD_PATH gets absolute roots (like *load-paths*), so a loader
    ;; scanning it finds files from any cwd, matching ClojureCLR. Prefer nil
    ;; over "", to unset the variable when there are no paths.
    (Environment/SetEnvironmentVariable
     "CLOJURE_LOAD_PATH"
     (when (seq roots) (string/join Path/PathSeparator roots)))
    (alter-var-root #'*load-paths*
                    (constantly
                     (into roots (remove (set roots)) -base-load-paths)))))

(defn set-load-path [val]
  (reset! -load-path (mapv absolute-path val))
  (update-load-path))

(defn add-load-path [path]
  (swap! -load-path conj (absolute-path path))
  (update-load-path))

(defn add-assembly-path [path]
  (swap! -assembly-path conj path))

(defn load-path [& paths]
  (doseq [p paths]
    (add-load-path p)))

(defn assembly-path [& paths]
  (doseq [p paths]
    (add-assembly-path p)))

(defn- dir-prefix
  "dir lower-cased and separator-terminated. The separator stops a prefix test
  matching a sibling; the case folding is for macOS and Windows."
  [dir]
  (let [sep (str Path/DirectorySeparatorChar)]
    (string/lower-case (cond-> dir (not (string/ends-with? dir sep)) (str sep)))))

(defn- under-any? [prefixes file]
  (let [path (string/lower-case (Path/GetFullPath file))]
    (boolean (some #(string/starts-with? path %) prefixes))))

(defn- assembly-file [asm]
  (when-not (.IsDynamic asm)
    (not-empty (.Location asm))))

(defn loaded-assembly-files
  "The files of the assemblies this process loaded from under a source path.
  The current directory sits on the load path to resolve task files, not as a
  source path, so matching it would take in every assembly beneath it."
  []
  (let [cwd      (dir-prefix (Path/GetFullPath "."))
        prefixes (->> (load-path-roots)
                      distinct
                      (filter #(Directory/Exists %))
                      (map dir-prefix)
                      (remove #(= cwd %)))]
    (for [asm   (.GetAssemblies AppDomain/CurrentDomain)
          :let  [file (assembly-file asm)]
          :when (and file (under-any? prefixes file))]
      file)))

(defn reference* [asms]
  (doseq [asm asms]
    (let [a (str asm)]
      (assembly-load-from a))))

(defmacro reference [& asms]
  `(reference* ~(mapv str asms)))

(defn establish-deps-edn
  "Resolve a deps.edn (with the given aliases) and put every resolved
  source path on the load path. Returns the basis. The 0-arity is the
  boot entry point: it activates the aliases under the :nos/aliases key,
  and when :nos/submodule-paths is present also adds the source paths of
  the vendored git submodules (its value is a path prefix to restrict to,
  or true for every submodule)."
  ([]
   (let [deps-file (basis/project-deps-file)
         deps-edn  (basis/read-project-deps deps-file)
         b         (establish-deps-edn deps-file (:nos/aliases deps-edn []))]
     (when-let [root (:nos/submodule-paths deps-edn)]
       (when (File/Exists ".gitmodules")
         (apply load-path
                (submodules/submodule-paths (slurp ".gitmodules")
                                            (when (string? root) root)))))
     b))
  ([deps-file aliases]
   (let [{:keys [classpath-paths] :as b} (basis/create-basis deps-file aliases)]
     (apply load-path classpath-paths)
     b)))
