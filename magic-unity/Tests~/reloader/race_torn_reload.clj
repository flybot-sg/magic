;; Proves the background-thread-eval hazard (see README.md): evaluating a source file
;; on one thread while another thread reads its vars exposes torn, mixed-version
;; states.
;;
;; The file under "reload" defines two vars that must always agree (k1, k2),
;; separated by filler forms — standing in for a real file where related defs
;; are far apart (integrated's biggest watched .cljc is 2,213 lines).
;; A reader thread spins on the invariant (= k1 k2) while the main thread
;; re-evals the file, exactly like ClojureReloader's watcher-thread eval races
;; the Unity main thread's dispatch!/render! var reads.
;;
;; Run on ClojureCLR through TornRaceHost.cs (run.sh, Probe 2), which binds *ns* to
;; user the way clojure.main does; the script is plain Clojure and also runs as
;; `clojure -M race_torn_reload.clj` on the JVM.
;; Expected (problem present): torn-observations > 0 — measured on ClojureCLR
;; 2026-09-04: 100% of reloads caught torn, ~96% of ALL reader samples saw k1 != k2
;; (the JVM run of 2026-07-16 measured ~90%).

(declare k1 k2) ; re-defed at runtime by each load-string below

(def n-filler 30)
(def n-reloads 100)

(defn src [tag]
  (str "(in-ns 'user)\n"
       "(def k1 " tag ")\n"
       (apply str (for [i (range n-filler)]
                    (str "(defn filler-" i " [x] (* x " i "))\n")))
       "(def k2 " tag ")\n"))

;; initial consistent state
(load-string (src ":v0"))

(def running (atom true))
(def reads (atom 0))
(def torn (atom 0))

(def reader
  (future
    (while @running
      (swap! reads inc)
      (when (not= k1 k2)
        (swap! torn inc)))))

(dotimes [i n-reloads]
  (load-string (src (str ":v" (inc i)))))

(reset! running false)
@reader
(println (format "reloads=%d reader-samples=%d torn-observations=%d"
                 n-reloads @reads @torn))
(if (pos? @torn)
  (println "TORN-STATE-PROVEN: concurrent reload produced observable mixed-version states")
  (println "TORN-STATE-NOT-REPRODUCED"))
(shutdown-agents)
