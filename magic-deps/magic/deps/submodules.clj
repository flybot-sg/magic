(ns magic.deps.submodules
  "Derive a flat source-path set from git submodules, so a project that vendors
  its dependencies as submodules can treat .gitmodules as the single source of
  truth for its deps.edn :paths instead of hand-maintaining the list."
  (:import [System.IO File Directory Path])
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(defn parse-gitmodules
  "Parse .gitmodules text into a vector of submodule maps {:name :path :url ...},
  one per [submodule \"...\"] block, in declaration order."
  [text]
  (->> (str/split-lines text)
       (map str/trim)
       (remove #(or (str/blank? %)
                    (str/starts-with? % "#")
                    (str/starts-with? % ";")))
       (reduce
        (fn [acc line]
          (cond
            (str/starts-with? line "[submodule")
            (conj acc {:name (second (re-find #"\"([^\"]*)\"" line))})

            (and (seq acc) (str/includes? line "="))
            (let [[k v] (map str/trim (str/split line #"=" 2))]
              (conj (pop acc) (assoc (peek acc) (keyword k) v)))

            :else acc))
        [])
       (filterv :path)))

(defn- deps-file
  "The deps file of the submodule at dir: deps-clr.edn when it has one, else
  deps.edn. Same preference basis.clj applies to a dependency and cljr to a
  project, so a library that keeps deps.edn for the JVM still contributes its
  CLR paths."
  [dir]
  (let [clr (Path/Combine dir "deps-clr.edn")
        std (Path/Combine dir "deps.edn")]
    (cond (File/Exists clr) clr
          (File/Exists std) std)))

(defn- src-dirs
  "Repo-relative source dirs the submodule at sub-path under repo contributes.
  Prefer the :paths of the submodule's own deps file; else whichever
  conventional source layouts are present on disk; else [\"src\"]."
  [repo sub-path]
  (let [dir      (Path/Combine repo sub-path)
        file     (deps-file dir)
        declared (when file
                   (try (:paths (edn/read-string (slurp file)))
                        (catch Exception _ nil)))]
    (if declared
      (mapv #(str sub-path "/" %) declared)
      (let [present (filterv #(Directory/Exists (Path/Combine dir %))
                             ["src" "src/main/clojure"])]
        (mapv #(str sub-path "/" %) (if (seq present) present ["src"]))))))

(defn submodule-paths
  "Flat vector of source paths, relative to repo, contributed by every
  submodule in repo's gitmodules-text whose path starts with root-prefix (nil
  or blank = every submodule). Lets .gitmodules drive a deps.edn :paths over
  an in-tree submodule layout."
  ([repo gitmodules-text] (submodule-paths repo gitmodules-text nil))
  ([repo gitmodules-text root-prefix]
   (->> (parse-gitmodules gitmodules-text)
        (map :path)
        (filter #(or (str/blank? root-prefix)
                     (= % root-prefix)
                     (str/starts-with? % (str root-prefix "/"))))
        (mapcat #(src-dirs repo %))
        distinct
        vec)))
