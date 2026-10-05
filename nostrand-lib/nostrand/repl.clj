(ns nostrand.repl
  (:require [clojure.string :as string]
            [clojure.core.server :as clj-server])
  (:import
   [System.IO StringWriter]
   [System.Collections Queue]
   [System.Text Encoding StringBuilder]
   [System.Net IPEndPoint IPAddress]
   [System.Net.Sockets UdpClient SocketException]))

(defn prompt []
  (str *ns* "> "))

(def socket-repl-running (atom true))

(defonce cli-output-queue (Queue/Synchronized (Queue.)))

(defn- random-port []
  (int (+ 1024 (* (rand) (- UInt16/MaxValue 1024)))))

(defn- available-socket [port]
  (try
    (UdpClient. (IPEndPoint. IPAddress/Any port))
    (catch SocketException e
      (available-socket (random-port)))))

(defn socket [{:keys [line-editor
                      env
                      history
                      port
                      send-buffer-size
                      receive-buffer-size
                      receive-timeout
                      on-start
                      on-eval]
               :or {history 500
                    port 11217
                    send-buffer-size (* 1024 5000)
                    receive-buffer-size (* 1024 5000)
                    receive-timeout 100
                    on-start (fn [_])
                    on-eval (fn [_])}
               :as args}]
  (let [^UdpClient socket (available-socket port)
        sb (StringBuilder.)]
    (set! (.. socket Client SendBufferSize) (int send-buffer-size))
    (set! (.. socket Client ReceiveBufferSize) (int receive-buffer-size))
    (set! (.. socket Client ReceiveTimeout) (int receive-timeout))
    (on-start (.. socket Client LocalEndPoint))
    (binding [*ns* (find-ns 'user)
              *out* (StringWriter. sb)
              *warn-on-reflection* *warn-on-reflection*
              *unchecked-math* *unchecked-math*]
      (loop [running @socket-repl-running]
        (when running
          (try
            (let [^IPEndPoint sender (IPEndPoint. IPAddress/Any 0)
                  in-bytes (.Receive socket (by-ref sender))]
              (if (> (.Length in-bytes) 0)
                (let [in-code (.GetString Encoding/UTF8 in-bytes)]
                  (try
                    (let [result (-> (read-string {:read-cond :allow} in-code)
                                     eval
                                     pr-str)
                          out-str (str sb)
                          out-bytes (.GetBytes Encoding/UTF8 out-str)
                          response-bytes (.GetBytes Encoding/UTF8 (str in-code out-str result "\n" (prompt)))]
                      (.Send socket response-bytes (.Length response-bytes) sender)
                      (on-eval {:code in-code :out out-str :result result})
                      (.Clear sb))
                    (catch Exception e
                      (let [ex-bytes (.GetBytes Encoding/UTF8 (str in-code e "\n" (prompt)))]
                        (.Send socket ex-bytes (.Length ex-bytes) sender)
                        (on-eval {:code in-code :exception e})
                        (.Clear sb)))))))
            (catch SocketException e))
          (recur @socket-repl-running))))))

(defn clojure-socket-server [opts]
  (let [opts (merge {:accept `clj-server/repl
                     :name "Clojure socket repl"}
                    opts)]
    (clj-server/start-server opts)
    opts))
