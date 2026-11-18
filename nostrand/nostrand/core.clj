(ns
 ^{:author "Ramsey Nasser"
   :doc "Core nostrand API containing load path, assemblies, and dependency functions."}
 nostrand.core
  (:require [clojure.string :as string]
            [magic.deps.basis :as basis]
            [magic.deps.load-path :as lp])
  (:import [System.IO Directory Path File]))

(defn- path-list
  "Absolute entries of a PathSeparator-joined list. Blank entries are dropped
  rather than rejected: they come from a stray separator, not a named path."
  [s]
  (into [] (comp (remove string/blank?) (map lp/absolute-path))
        (string/split (or s "") (re-pattern (str Path/PathSeparator)))))

;; No cwd default: the cwd here is the host's; establish-project seeds the root.
(def ^:private -env-assembly-path
  (path-list (Environment/GetEnvironmentVariable "MONO_PATH")))

(def ^:private -env-load-path
  (path-list (Environment/GetEnvironmentVariable "CLOJURE_LOAD_PATH")))

(def -assembly-path (atom -env-assembly-path))

(def -load-path (atom -env-load-path))

(defonce ^{:private true
           :doc "*load-paths* as the runtime left it, set when this namespace is
  first loaded, so a host must load nostrand.core before adding any project root."}
  -base-load-paths (vec *load-paths*))

(def ^:private -project-root
  (atom nil))

(defn project-root
  "Directory the project deps file and its relative :paths resolve against."
  []
  (or @-project-root
      (throw (ex-info "No project root: establish-project has not run" {}))))

(defn project-deps-file
  []
  (basis/project-deps-file (project-root)))

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
                    (constantly (lp/prepend-load-paths roots -base-load-paths)))))

(defn set-load-path [val]
  (reset! -load-path (mapv lp/absolute-path val))
  (update-load-path))

(defn add-load-path [path]
  (swap! -load-path conj (lp/absolute-path path))
  (update-load-path))

(defn add-assembly-path [path]
  (swap! -assembly-path conj (lp/absolute-path path)))

(defn set-assembly-path
  [val]
  (reset! -assembly-path (mapv lp/absolute-path val)))

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
  The project root sits on the load path to resolve task files, not as a
  source path, so matching it would take in every assembly beneath it."
  []
  (let [root     (dir-prefix (project-root))
        prefixes (->> (load-path-roots)
                      (filter #(Directory/Exists %))
                      (map dir-prefix)
                      (remove #(= root %)))]
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
  "Resolve deps-file (with the given aliases) and put every resolved source
  path on the load path. Returns the basis."
  [deps-file aliases]
  (let [{:keys [classpath-paths] :as b} (basis/create-basis deps-file aliases)]
    (apply load-path classpath-paths)
    b))

(defn establish-project
  "Make root the project root and reset both searches to the env-var roots
  plus root, replacing any previous project, then add the source paths from
  root's deps file.
  extra-roots are source roots searched after root; they do not join the
  assembly search. Returns the basis, or nil when root has no deps file."
  ([root] (establish-project root nil))
  ([root extra-roots]
   (let [root (lp/absolute-path root)]
     (when-not (Directory/Exists root)
       (throw (ex-info (str "Project root is not a directory: " root) {:root root})))
     (reset! -project-root root)
     (set-load-path (into (conj -env-load-path root) extra-roots))
     (set-assembly-path (conj -env-assembly-path root))
     (when-let [b (basis/project-basis root)]
       (apply load-path (:classpath-paths b))
       b))))
