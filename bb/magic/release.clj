(ns magic.release
  "Release plumbing: dependency report, dist verification, tarball, tagging."
  (:require [babashka.fs :as fs]
            [babashka.tasks :refer [shell]]
            [clojure.edn :as edn]))

(defn- version []
  (:version (edn/read-string (slurp "version.edn"))))

(defn outdated! []
  (println "=== GitHub Actions (antq) ===")
  (shell {:continue true}
         "clojure" "-Sdeps"
         "{:deps {com.github.liquidz/antq {:mvn/version \"RELEASE\"}}}"
         "-M" "-m" "antq.core")
  (doseq [proj ["clojure-runtime/Clojure.csproj"
                "magic-runtime/Magic.Runtime/Magic.Runtime.csproj"
                "magic-runtime/Magic.Runtime.Callsites/Magic.Runtime.Callsites.csproj"
                "nostrand/NostrandMain.csproj"]]
    (println)
    (println (str "=== " proj " (NuGet) ==="))
    (shell {:continue true} "dotnet" "list" proj "package" "--outdated"))
  (println)
  (println "Note: Nostrand `deps.edn` git deps are pinned by sha and not version-checked by antq. Inspect manually if used."))

(defn verify-dist! []
  ;; `nos version` boots the whole runtime, so a missing launcher,
  ;; runtime DLL or stdlib .clj.dll exits non-zero.
  ;; The magic-unity DLLs are tracked in git and tested by `check-drift` on push.
  (println "Running nos version:")
  (shell "nostrand/bin/Release/net471/nos" "version"))

(defn release-tarball! []
  (let [dist-name (str "nostrand-v" (version) "-mono")
        staging   (str "target/" dist-name)
        tarball   (str "target/" dist-name ".tar.gz")
        smoke-dir "target/smoke"]
    (fs/delete-tree staging)
    (fs/delete-tree smoke-dir)
    (fs/create-dirs staging)
    ;; bin/Release/net471/ is self-contained: nos launcher, NostrandMain.exe,
    ;; runtime DLLs, all .clj.dll, and the nostrand/ source subdir that the
    ;; runtime loads at startup. Recursive copy of contents.
    (shell "cp" "-R" "nostrand/bin/Release/net471/." (str staging "/"))
    (shell {:dir "target"} "tar" "czf" (str dist-name ".tar.gz") dist-name)
    (println "Wrote" tarball)
    (println "Smoke: extract + run nos version")
    (fs/create-dirs smoke-dir)
    (shell "tar" "xzf" tarball "-C" smoke-dir)
    (shell (str smoke-dir "/" dist-name "/nos") "version")))

(defn tag! []
  (let [tag (str "v" (version))]
    (println "Creating tag:" tag)
    (shell "git" "tag" "-a" tag "-m" (str "Release " (version)))
    (println "Pushing tag to origin...")
    (shell "git" "push" "origin" tag)
    (println (str "Done! Release workflow will validate " tag))))
