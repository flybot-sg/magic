(ns preplserver
  "nos task: start a socket prepl server (clojure.core.server/io-prepl) on a
  warm MAGIC runtime, so a client can eval forms against it on the fly. This
  is the MAGIC analogue of a Clojure nREPL for dev: start it, hand the port
  to a client, send forms, read structured {:tag :ret/:out/:err/:tap ...}
  replies. Started via `bb prepl-server [port]` (default 5555)."
  (:require [clojure.core.server :as server]))

(defn start [& [port]]
  (let [port (if port (Int32/Parse (str port)) 5555)]
    ;; pass a concrete IPAddress so start-server skips Dns/GetHostEntry, which
    ;; on .NET can resolve "127.0.0.1" to ::1 or the LAN IP and bind there.
    (server/start-server {:name "magic-prepl"
                          :address System.Net.IPAddress/Loopback
                          :port port
                          :accept 'clojure.core.server/io-prepl})
    (println (str "MAGIC prepl (io-prepl) listening on 127.0.0.1:" port))
    (println (str "  eval a form:  bb prepl-eval '<form>'  (port " port ")"))
    (flush)
    ;; start-server spawns daemon threads; keep the process alive to serve.
    (loop [] (System.Threading.Thread/Sleep 86400000) (recur))))
