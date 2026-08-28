(ns
    ^{:author "Ramsey Nasser"
      :doc "Core nostrand API containing load path, assemblies, and dependency functions."}
    nostrand.core
  (:require [clojure.string :as string]
            [nostrand.deps.basis :as basis]
            [nostrand.deps.submodules :as submodules])
  (:import [System.IO Directory Path File]))

(def -assembly-path
  (atom (string/split (or (Environment/GetEnvironmentVariable "MONO_PATH") ".")
                      (re-pattern (str Path/PathSeparator)))))

(def -load-path
  (atom (string/split (or (Environment/GetEnvironmentVariable "CLOJURE_LOAD_PATH") ".")
                      (re-pattern (str Path/PathSeparator)))))

(defn- absolute-load-path []
  (mapv #(Path/GetFullPath %) @-load-path))

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
  (let [abs-paths (absolute-load-path)]
    ;; CLOJURE_LOAD_PATH gets absolute roots (like *load-paths*), so a loader
    ;; scanning it finds files from any cwd, matching ClojureCLR.
    (Environment/SetEnvironmentVariable
      "CLOJURE_LOAD_PATH"
      (string/join Path/PathSeparator abs-paths))
    (alter-var-root #'*load-paths*
                    (fn [load-paths]
                      (mapv
                       #(System.IO.Path/GetFullPath %)
                       (concat @-load-path load-paths))))))

(defn set-load-path [val]
  (reset! -load-path val)
  (update-load-path))

(defn add-load-path [path]
  (swap! -load-path conj path)
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
        prefixes (->> (absolute-load-path)
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
