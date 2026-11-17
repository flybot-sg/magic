(ns nostrand.repl
  (:require [clojure.core.server :as clj-server])
  (:import
   [Mono.Terminal LineEditor]
   [System.IO StringWriter EndOfStreamException]
   [System.Text Encoding StringBuilder]
   [System.Threading Thread ThreadStart]
   [System.Net IPEndPoint IPAddress]
   [System.Net.Sockets UdpClient SocketException]))

(defn- prompt []
  (str *ns* "> "))

(defn- continue-prompt []
  (let [l (dec (count (prompt)))]
    (str (apply str (repeat l ".")) " ")))

(defn- balanced? [s]
  (try
    (read-string {:read-cond :allow} s)
    true
    (catch EndOfStreamException e
      false)))

(def socket-repl-running (atom true))

(defn- new-line-editor []
  (LineEditor. "nostrand" 500))

(defn cli-repl
  ([] (cli-repl nil))
  ([{:keys [line-editor]
     :or {line-editor (new-line-editor)}}]
   (binding [*ns* (find-ns 'user)
             *warn-on-reflection* *warn-on-reflection*
             *unchecked-math* *unchecked-math*]
     (loop [s (.Edit line-editor (prompt) "")]
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
             (recur (.Edit line-editor (prompt) "")))))))
   (reset! socket-repl-running false)))

(defn- random-port []
  (int (+ 1024 (* (rand) (- UInt16/MaxValue 1024)))))

(defn- available-socket [port]
  (try
    (UdpClient. (IPEndPoint. IPAddress/Any port))
    (catch SocketException e
      (available-socket (random-port)))))

(defn- echo
  "Shows a socket eval on the console. Runs with *out* bound to the reply
   buffer, so it writes to the console directly."
  [line-editor {:keys [code out result exception]}]
  (Console/WriteLine)
  (Console/Write (prompt))
  (if exception
    (Nostrand.Terminal/Message "Exception" (str exception) ConsoleColor/Yellow)
    (do (Console/Write code)
        (when-not (empty? out)
          (Nostrand.Terminal/Message out ConsoleColor/Gray))
        (Nostrand.Terminal/Message result ConsoleColor/Gray)))
  (.SnapHomeRowToCursor line-editor)
  (.StopEdit line-editor))

(defn socket
  [{:keys [line-editor
           port
           send-buffer-size
           receive-buffer-size
           receive-timeout]
    :or {port 11217
         send-buffer-size (* 1024 5000)
         receive-buffer-size (* 1024 5000)
         receive-timeout 100}
    :as args}]
  (let [^UdpClient socket (available-socket port)
        sb (StringBuilder.)]
    (set! (.. socket Client SendBufferSize) (int send-buffer-size))
    (set! (.. socket Client ReceiveBufferSize) (int receive-buffer-size))
    (set! (.. socket Client ReceiveTimeout) (int receive-timeout))
    (Nostrand.Terminal/Message "REPL" (.. socket Client LocalEndPoint) ConsoleColor/Blue)
    (.Start (Thread. (gen-delegate ThreadStart [] (cli-repl args))))
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
                          response-bytes (.GetBytes Encoding/UTF8 (str in-code out-str result "\n" (prompt)))]
                      (.Send socket response-bytes (.Length response-bytes) sender)
                      (echo line-editor {:code in-code :out out-str :result result})
                      (.Clear sb))
                    (catch Exception e
                      (let [ex-bytes (.GetBytes Encoding/UTF8 (str in-code e "\n" (prompt)))]
                        (.Send socket ex-bytes (.Length ex-bytes) sender)
                        (echo line-editor {:code in-code :exception e})
                        (.Clear sb)))))))
            (catch SocketException e))
          (recur @socket-repl-running))))))

(defn clojure-socket-server [opts]
  (let [opts (merge {:accept `clj-server/repl
                     :name "Clojure socket repl"}
                    opts)]
    (clj-server/start-server opts)
    opts))
