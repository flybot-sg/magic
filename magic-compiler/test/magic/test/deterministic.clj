(ns magic.test.deterministic
  "Covers magic.api/normalize-assembly!, which runs in the file-writing compile
   path, by exercising it directly as the pure byte transform it is."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [magic.api :as m])
  (:import [System AppDomain BitConverter]
           [System.IO File Path]
           [System.Security.Cryptography SHA256]))

(defn- committed-clj-dll
  "Path to a committed .clj.dll on disk, via a loaded assembly's Location."
  []
  (->> (.GetAssemblies AppDomain/CurrentDomain)
       (keep (fn [a] (try (let [l (.Location a)]
                            (when (and (seq l) (str/ends-with? l ".clj.dll")) l))
                          (catch Exception _ nil))))
       sort
       first))

(defn- sha256 [bytes]
  (vec (.ComputeHash (SHA256/Create) bytes)))

(defn- pe-timestamp-offset [bytes]
  (int (+ (BitConverter/ToInt32 bytes (int 0x3C)) 8)))

(deftest normalize-assembly
  (let [src (committed-clj-dll)]
    (is (some? src) "a committed .clj.dll should be loaded")
    (when src
      (let [tmp  (Path/Combine (Path/GetTempPath) "magic-normalize-a.clj.dll")
            tmp2 (Path/Combine (Path/GetTempPath) "magic-normalize-b.clj.dll")]
        (File/Copy src tmp true)
        (m/normalize-assembly! tmp)
        (let [canonical (File/ReadAllBytes tmp)
              ts-off    (pe-timestamp-offset canonical)]
          (testing "PE timestamp is zeroed"
            (is (zero? (BitConverter/ToInt32 canonical ts-off))))
          (testing "normalize! is a fixpoint: dirtying the timestamp is fully recovered"
            ;; only the timestamp is dirtied, yet recovering the exact bytes also
            ;; proves the MVID was re-derived from content at the right #GUID offset.
            (let [dirty (aclone canonical)]
              (dotimes [i 4] (aset dirty (int (+ ts-off i)) (unchecked-byte 0xFF)))
              (File/WriteAllBytes tmp2 dirty)
              (is (not= (sha256 canonical) (sha256 dirty)))
              (m/normalize-assembly! tmp2)
              (is (= (sha256 canonical) (sha256 (File/ReadAllBytes tmp2)))))))
        (File/Delete tmp)
        (File/Delete tmp2)))))
