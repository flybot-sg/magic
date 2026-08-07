(ns magic.nos
  "Invocation of the repo-built Nostrand binary, the bootstrap pass, and the
   prepl client."
  (:require [babashka.fs :as fs]
            [babashka.tasks :refer [shell]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [magic.drift :as drift]
            [magic.log :as log]))

(def exe "nostrand/bin/Release/net471/NostrandMain.exe")

(defn check-built!
  "Abort unless the Nostrand binary has been built."
  []
  (when-not (fs/exists? exe)
    (log/fail! "nostrand not built"
               "Nostrand not built. Run `bb build` first (or `bb build-runtime` if everything else is current).")))

(defn nos!
  "Run a Nostrand task from magic-compiler/ with the repo-built binary."
  [task & args]
  (apply shell {:dir "magic-compiler"} "mono" (str "../" exe) task args))

(defn bootstrap!
  "One bootstrap pass: compile the compiler with whatever compiler is currently
   in references/, deploy the result over it, re-record the manifest. The
   compiler that ran was the previous one, so one pass is not the fixpoint and
   a compiler change needs two calls."
  [args]
  (drift/touch-dlls!)
  ;; magic.api/compile-file skips DLLs that already exist on disk, so
  ;; delete bootstrap/ first to force re-compilation.
  (fs/delete-tree (fs/file "magic-compiler" "bootstrap"))
  (apply nos! "build/bootstrap" args)
  (shell "dotnet build -t:Bootstrap;MagicUnity")
  (drift/record!))

(defn prepl-eval!
  "Send one form to a running prepl server and print the reply maps until :ret."
  [args]
  (let [[port args] (if (= ":port" (first args))
                      [(Integer/parseInt (second args)) (drop 2 args)]
                      [5555 args])
        form (str/join " " args)]
    (when (str/blank? form)
      (log/fail! "missing form"
                 "usage: bb prepl-eval '<form>'  (or bb prepl-eval :port N '<form>')"))
    (with-open [sock (java.net.Socket. "127.0.0.1" ^long port)]
      (.setSoTimeout sock 15000)
      (let [out (io/writer (.getOutputStream sock))
            in  (io/reader (.getInputStream sock))]
        (.write out (str form "\n"))
        (.flush out)
        (loop []
          (when-let [line (.readLine in)]
            (println line)
            (when-not (str/includes? line ":tag :ret")
              (recur))))))))
