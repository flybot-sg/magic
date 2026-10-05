(ns
 ^{:author "Ramsey Nasser"}
 nostrand.cli
  (:import
   [Nostrand Nostrand]
   [Mono.Terminal LineEditor]
   [System.IO Directory Path EndOfStreamException]
   [System.Threading Thread ThreadStart])
  (:require [nostrand.repl :as repl]
            [clojure.string :as string]
            clojure.repl))

(defn- msg
  ([header body]
   (Nostrand.Terminal/Message header body))
  ([header body color]
   (Nostrand.Terminal/Message header body color)))

(defn version []
  (msg "Nostrand" (Nostrand/Version) ConsoleColor/Cyan)
  (msg "Clojure.Runtime" (Nostrand/ClojureRuntimeVersion) ConsoleColor/Cyan)
  (msg "Magic.Runtime" (Nostrand/MagicRuntimeVersion) ConsoleColor/Cyan)
  (msg "Clojure" (clojure-version) ConsoleColor/Cyan)
  (msg "Runtime" (str (Environment/get_Version)
                      " (" (Environment/get_OSVersion) ")")
       ConsoleColor/DarkGray))

(defn where
  "Print the directory the running host loaded Clojure.dll from, as bare
  stdout for shell capture. With a file name, print that file's full path.
  Usage: nos where                  ; the runtime assemblies dir
         nos where Clojure.dll      ; full path of one assembly"
  ([] (println (-> (.Assembly clojure.lang.RT) .Location Path/GetDirectoryName)))
  ([file] (println (Path/Combine (-> (.Assembly clojure.lang.RT) .Location Path/GetDirectoryName)
                                 (str file)))))

(defn- continue-prompt []
  (let [l (dec (count (repl/prompt)))]
    (str (apply str (repeat l ".")) " ")))

(defn- balanced? [s]
  (try
    (read-string {:read-cond :allow} s)
    true
    (catch EndOfStreamException e
      false)))

(defn- cli [{:keys [history
                    line-editor
                    env]
             :or {history 500
                  line-editor (LineEditor. "nostrand" 500)}
             :as args}]
  (binding [*ns* (find-ns 'user)
            *warn-on-reflection* *warn-on-reflection*
            *unchecked-math* *unchecked-math*]
    (loop [s (.Edit line-editor (repl/prompt) "")]
      (when s
        (if-not (balanced? s)
          (recur (str s "\n" (.Edit line-editor (continue-prompt) "")))
          (do
            (try
              (-> (read-string {:read-cond :allow} s)
                  eval
                  pr-str
                  (Nostrand.Terminal/Message ConsoleColor/Gray))
              (catch Exception e
                (Nostrand.Terminal/Message "Exception" (str e) ConsoleColor/Yellow)))
            (recur (.Edit line-editor (repl/prompt) "")))))))
  (reset! repl/socket-repl-running false))

(defn cli-repl
  ([] (cli-repl nil))
  ([args]
   (cli args)))

(defn- echo [line-editor]
  (fn [{:keys [code out result exception]}]
    ;; cli print
    (Console/WriteLine)
    (Console/Write (repl/prompt))
    (if exception
      (Nostrand.Terminal/Message "Exception" (str exception) ConsoleColor/Yellow)
      (do (Console/Write code)
          (when-not (empty? out)
            (Nostrand.Terminal/Message out ConsoleColor/Gray))
          (Nostrand.Terminal/Message result ConsoleColor/Gray)))
    (.SnapHomeRowToCursor line-editor)
    (.StopEdit line-editor)))

(defn socket-repl [args]
  (repl/socket
   (assoc args
          :on-start (fn [endpoint]
                      (Nostrand.Terminal/Message "REPL" endpoint ConsoleColor/Blue)
                      (.Start (Thread. (gen-delegate ThreadStart [] (cli args)))))
          :on-eval (echo (:line-editor args)))))

(defn repl
  ([]
   (version)
   (cli-repl))
  ([port]
   (version)
   (cli-repl)))

(defn tasks []
  (let [ns-syms
        (->> (Directory/GetFiles "." "*.clj")
             (map #(-> %
                       (string/replace "./" "")
                       (string/replace ".clj" "")
                       symbol)))]
    (doseq [s ns-syms]
      (require s)
      (let [fns (->> s
                     find-ns
                     ns-publics
                     vals)]
        (doseq [f fns]
          ((var clojure.repl/print-doc) (meta f)))))))

(defn clojure-socket-repl [args]
  (print "Starting Clojure socket repl...")
  (let [opts (repl/clojure-socket-server args)]
    (println "done ")
    (println "Started socket repl with Options: " opts)
    (repl)))
