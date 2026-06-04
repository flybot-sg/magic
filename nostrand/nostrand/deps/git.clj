(ns nostrand.deps.git
  (:import [System.IO Directory])
  (:require [clojure.string :as str]
            [nostrand.deps.shell :refer [sh require-command]]))

(defn- url->rel
  "Cache-relative path for a git url: drops the scheme, the user@, and
  the .git suffix, and normalises the host:path separator to a slash.
  git@host:group/repo.git -> host/group/repo."
  [url]
  (-> url
      (str/replace #"^\w+://" "")
      (str/replace #"^[^@/]+@" "")
      (str/replace #":" "/")
      (str/replace #"\.git$" "")))

(defn- run-git!
  "Run git, throwing with captured stderr on a non-zero exit. Returns trimmed
  stdout."
  [& args]
  (let [{:keys [exit out err]} (apply sh "git" args)]
    (when-not (zero? exit)
      (throw (ex-info "git command failed" {:args (vec args) :err err})))
    (str/trim (or out ""))))

(defn same-commit?
  "Whether two git refs denote the same commit: equal, or one a hex prefix of
  the other (>= 7 chars, git's unambiguous short-sha floor). Lets a short sha
  and the full sha it abbreviates compare equal."
  [a b]
  (boolean
    (when (and a b)
      (let [a (str/lower-case (str a))
            b (str/lower-case (str b))]
        (or (= a b)
            (and (>= (min (count a) (count b)) 7)
                 (re-matches #"[0-9a-f]+" a)
                 (re-matches #"[0-9a-f]+" b)
                 (or (str/starts-with? a b)
                     (str/starts-with? b a))))))))

(defn- rev-parse
  "Full commit sha that ref denotes inside dir, or nil if it does not resolve."
  [dir ref]
  (try (run-git! "-C" dir "rev-parse" (str ref "^{commit}"))
       (catch Exception _ nil)))

(defn- verify!
  "Check the checkout in dir against the pin and return its full HEAD sha.
  Throws when a pinned sha does not match HEAD (a stale or corrupt cache, a
  wrong pin); warns when a pinned tag resolves to a different commit than HEAD
  (a moved or inconsistent tag)."
  [dir url sha tag]
  (let [head (run-git! "-C" dir "rev-parse" "HEAD")]
    (when (and sha (not (same-commit? head sha)))
      (throw (ex-info "git checkout does not match pinned sha"
                      {:url url :pinned-sha sha :head head :dir dir})))
    (when tag
      (let [tag-sha (rev-parse dir tag)]
        (when (and tag-sha (not (same-commit? tag-sha head)))
          (println "WARN:" url "tag" (str tag) "resolves to" tag-sha
                   "but the pinned commit is" head))))
    head))

(defn clone-at!
  "Clone url and detached-checkout the pinned commit (sha if given, else tag)
  into dir, idempotently, then verify the checkout against the pin. A populated
  dir is reused but still verified. Returns the full checked-out sha."
  [url sha tag dir]
  (require-command "git")
  (when-not (Directory/Exists dir)
    (let [ref (or sha tag)]
      (println (str "Cloning " url (when ref (str " at " ref))))
      (run-git! "clone" "--quiet" url dir)
      (when ref
        (run-git! "-C" dir "checkout" "--quiet" (str ref)))))
  (verify! dir url sha tag))

(defn procure!
  "Clone a deps.edn git coord into the content-addressed cache under root and
  verify the pin. Keyed by the requested sha (or tag). Returns {:dir :sha},
  where :sha is the full resolved commit."
  [root url sha tag]
  (let [dir (str root "/" (url->rel url) "/" (or sha tag))]
    {:dir dir :sha (clone-at! url sha tag dir)}))
