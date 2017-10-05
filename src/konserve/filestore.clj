(ns konserve.filestore
  "Bare file-system implementation."
  (:require [konserve.serializers :as ser]
            [konserve.core :refer [go-locked]]
            [clojure.java.io :as io]
            [hasch.core :refer [uuid]]

            [clojure.core.async :as async
             :refer [<!! <! >! timeout chan alt! go go-loop close! put!]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [konserve.protocols :refer [PEDNAsyncKeyValueStore
                                        -exists? -get-in -update-in -dissoc -assoc-in
                                        PBinaryAsyncKeyValueStore -bget -bassoc
                                        -serialize -deserialize]])
  (:import [java.io
            DataInputStream DataOutputStream
            FileInputStream FileOutputStream
            ByteArrayInputStream]
           [java.nio.channels FileChannel AsynchronousFileChannel CompletionHandler]
           [java.nio ByteBuffer]
           [java.nio.file Files StandardCopyOption FileSystems Path OpenOption
            StandardOpenOption]))

;; A useful overview over fsync on Linux:
;; https://www.usenix.org/conference/osdi14/technical-sessions/presentation/pillai

(defn- on-windows? []
  (>= (.indexOf (.toLowerCase (System/getProperty "os.name")) "win") 0))


(defn- sync-folder [folder]
  (when-not (on-windows?) ;; not necessary (and possible) on windows
    (let [p (.getPath (FileSystems/getDefault) folder (into-array String []))
          fc (FileChannel/open p (into-array OpenOption []))]
      (.force fc true)
      (.close fc))))


(defn delete-store
  "Permanently deletes the folder of the store with all files."
  [folder]
  (let [f (io/file folder)]
    (doseq [c (.list f)]
      (.delete (io/file (str folder "/" c))))
    (.delete f)
    (try
      (sync-folder folder)
      (catch Exception e
        nil))))


(defn list-keys
  "Lists all keys in this binary store. This operation *does not block concurrent operations* and might return an outdated key set. Keys of binary blobs are not tracked atm."
  [{:keys [folder serializer read-handlers] :as store}]
  (let [fns (->> (io/file folder)
                 .list
                 seq
                 (filter #(re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                                      %))
                 (map (fn [fn]
                        (go-locked
                         store fn
                         (let [f (io/file (str folder "/" fn))]
                           (when (.exists f)
                             (let [fis (DataInputStream. (FileInputStream. f))]
                               (try
                                 (first (-deserialize serializer read-handlers fis))
                                 (catch Exception e
                                   (ex-info "Could not read key."
                                            {:type :read-error
                                             :key fn
                                             :exception e}))
                                 (finally
                                   (.close fis)))))))))
                 async/merge
                 (async/into #{}))]
        fns))


(defrecord FileSystemStore [folder serializer read-handlers write-handlers locks config]
  PEDNAsyncKeyValueStore
  (-exists? [this key]
    (let [fn (uuid key)
          bfn (str "B_" (uuid key))
          f (io/file (str folder "/" fn))
          bf (io/file (str folder "/" bfn))
          res (chan)]
      (put! res (or (.exists f) (.exists bf)))
      (close! res)
      res))

  ;; non-blocking async version
  (-get-in [this key-vec]
    (let [[fkey & rkey] key-vec
          fn (uuid fkey)
          f (io/file (str folder "/" fn))
          res-ch (chan)]
      (if-not (.exists f)
        (close! res-ch)
        (try
          (let [ac (AsynchronousFileChannel/open (.getPath (FileSystems/getDefault)
                                                           (str folder "/" fn)
                                                           (into-array String []))
                                                 (into-array StandardOpenOption
                                                             [StandardOpenOption/READ]))
                bb (ByteBuffer/allocate (.size ac))]
            (.read ac
                   bb
                   0
                   nil
                   (proxy [CompletionHandler] []
                     (completed [res att]
                       (let [bais (ByteArrayInputStream. (.array bb))]
                         (try
                           (put! res-ch
                                 (get-in
                                  (second (-deserialize serializer read-handlers bais)) 
                                  rkey))
                           (catch Exception e
                             (ex-info "Could not read key."
                                      {:type :read-error
                                       :key fkey
                                       :exception e}))
                           (finally
                             (.close ac)))))
                     (failed [t att]
                       (put! res-ch (ex-info "Could not read key."
                                             {:type :read-error
                                              :key fkey
                                              :exception t}))
                       (.close ac)))))
          (catch Exception e
            (put! res-ch (ex-info "Could not read key."
                               {:type :read-error
                                :key fkey
                                :exception e})))))
      res-ch))

  (-update-in [this key-vec up-fn]
    (async/thread
      (let [[fkey & rkey] key-vec
            fn (uuid fkey)
            f (io/file (str folder "/" fn))
            new-file (io/file (str folder "/" fn ".new"))]
        (let [old (when (.exists f)
                    (let [fis (DataInputStream. (FileInputStream. f))]
                      (try
                        (second (-deserialize serializer read-handlers fis)) 
                        (catch Exception e
                          (ex-info "Could not read key."
                                   {:type :read-error
                                    :key fkey
                                    :exception e}))
                        (finally
                          (.close fis)))))
              fos (FileOutputStream. new-file)
              dos (DataOutputStream. fos)
              fd (.getFD fos)
              new (if-not (empty? rkey)
                    (update-in old rkey up-fn)
                    (up-fn old))]
          (if (instance? Throwable old)
            old ;; return read error
            (try
              (-serialize serializer dos write-handlers [key-vec new])
              (.flush dos)
              (when (:fsync config)
                (.sync fd))
              (.close dos)
              (Files/move (.toPath new-file) (.toPath f)
                          (into-array [StandardCopyOption/ATOMIC_MOVE]))
              (when (:fsync config)
                (sync-folder folder))
              [(get-in old rkey)
               (get-in new rkey)]
              (catch Exception e
                (.delete new-file)
                ;; TODO maybe need fsync new-file here?
                (ex-info "Could not write key."
                         {:type :write-error
                          :key fkey
                          :exception e}))
              (finally
                (.close dos))))))))

  (-assoc-in [this key-vec val] (-update-in this key-vec (fn [_] val)))

  (-dissoc [this key]
    (async/thread
      (let [fn (uuid key)
            f (io/file (str folder "/" fn))]
        (.delete f)
        (when (:fsync config)
          (sync-folder folder))
        nil)))

  PBinaryAsyncKeyValueStore
  (-bget [this key locked-cb]
    (let [fn (str "B_" (uuid key))
          f (io/file (str folder "/" fn))
          res-ch (chan)]
      (if-not (.exists f)
        (close! res-ch)
        (try
          (let [ac (AsynchronousFileChannel/open (.getPath (FileSystems/getDefault)
                                                           (str folder "/" fn)
                                                           (into-array String []))
                                                 (into-array StandardOpenOption
                                                             [StandardOpenOption/READ]))
                bb (ByteBuffer/allocate (.size ac))]
            (.read ac
                   bb
                   0
                   nil
                   (proxy [CompletionHandler] []
                     (completed [res att]
                       (let [bais (ByteArrayInputStream. (.array bb))]
                         (try
                           (locked-cb {:input-stream bais
                                       :size (.length f)
                                       :file f})
                           (catch Exception e
                             (ex-info "Could not read key."
                                      {:type :read-error
                                       :key key
                                       :exception e}))
                           (finally
                             (close! res-ch)
                             (.close ac)))))
                     (failed [t att]
                       (put! res-ch (ex-info "Could not read key."
                                             {:type :read-error
                                              :key key
                                              :exception t}))
                       (close! res-ch)
                       (.close ac)))))
          (catch Exception e
            (put! res-ch (ex-info "Could not read key."
                               {:type :read-error
                                :key key
                                :exception e})))))
      res-ch))

  (-bassoc [this key input]
    (let [fn (uuid key)
          f (io/file (str folder "/B_" fn))
          new-file (io/file (str folder "/B_" fn ".new"))]
      (async/thread
        (let [fos (FileOutputStream. new-file)
              dos (DataOutputStream. fos)
              fd (.getFD fos)]
          (try
            (io/copy input dos)
            (.flush dos)
            (when (:fsync config)
              (.sync fd))
            (.close fos) ;; required for windows
            (Files/move (.toPath new-file) (.toPath f)
                        (into-array [StandardCopyOption/ATOMIC_MOVE]))
            (when (:fsync config)
              (sync-folder folder))
            (catch Exception e
              (.delete new-file)
              (ex-info "Could not write key."
                       {:type :write-error
                        :key key
                        :exception e}))
            (finally
              (.close fos))))))))

(defmethod print-method FileSystemStore
  [store writer]
  (.write writer (str "FileStore[\"" (:folder store ) ", " (.hasheq store) "\"]")))

(defn new-fs-store
  [path & {:keys [serializer read-handlers write-handlers config]
           :or {serializer (ser/fressian-serializer)
                read-handlers (atom {})
                write-handlers (atom {})
                config {:fsync true}}}]
  (let [f (io/file path)
        locks (atom {})
        test-file (io/file (str path "/" (java.util.UUID/randomUUID)))]
    (when-not (.exists f)
      (.mkdir f))
    ;; simple test to ensure we can write to the folder
    (when-not (.createNewFile test-file)
      (throw (ex-info "Cannot write to folder." {:type :not-writable
                                                 :folder path})))
    (.delete test-file)
    (go
      (map->FileSystemStore {:folder path
                             :serializer serializer
                             :read-handlers read-handlers
                             :write-handlers write-handlers
                             :locks locks
                             :config config}))))




(comment


(def ac
  (AsynchronousFileChannel/open (.getPath (FileSystems/getDefault)
                                          "/tmp/atest"
                                          (into-array String []))
                                (into-array StandardOpenOption
                                            [StandardOpenOption/WRITE
                                             StandardOpenOption/CREATE])))

(proxy [CompletionHandler] []
  (completed [res att]
    (prn "completed" res att))
  (failed [t att]
    (prn "failed" t att)))

(.write ac (ByteBuffer/wrap (.getBytes "Hello World")) 0
        nil
        (proxy [CompletionHandler] []
          (completed [res att]
            (prn "completed" res att))
          (failed [t att]
            (prn "failed" t att))))

(def ac2
  (AsynchronousFileChannel/open (.getPath (FileSystems/getDefault)
                                          "/tmp/atest"
                                          (into-array String []))
                                (into-array StandardOpenOption
                                            [StandardOpenOption/READ])))

(.size ac2)

(let [bb (ByteBuffer/allocate (.size ac2))]
  (.read ac2
         bb
         0
         nil
         (proxy [CompletionHandler] []
           (completed [res att]
             (prn "completed" res att (String. (.array bb))))
           (failed [t att]
             (prn "failed" t att)))))

(def foo *1)

(deref foo)




  (def store (<!! (new-fs-store "/tmp/store2")))


  (<!! (-assoc-in store [:bar] 42))

  (<!! (-update-in store [:bar] inc))

  (<!! (-get-in store [:bar]))

  (<!! (-dissoc store :bar))


  (<!! (-assoc-in store [:foo] 42))
  (<!! (-get-in store [:foo]))

  ;; investigate https://github.com/stuartsierra/parallel-async
  (let [res (chan (async/sliding-buffer 1))
        v (vec (range 5000))]
    (time (->>  (range 5000)
                (map #(-update-in store [%] (fn [_] v)))
                async/merge
                (async/pipeline-blocking 4 res identity)
                <!!))) ;; 38 secs
  (<!! (-get-in store [2000]))

  (let [res (chan (async/sliding-buffer 1))
        ba (byte-array (* 10 1024) (byte 42))]
    (time (->>  (range 10000)
                (map #(-bassoc store % ba))
                async/merge
                (async/pipeline-blocking 4 res identity)
                #_(async/into [])
                <!!))) ;; 19 secs


  (let [v (vec (range 5000))]
    (time (doseq [i (range 10000)]
            (<!! (-assoc-in store [i] i))))) ;; 19 secs

  (time (doseq [i (range 10000)]
          (<!! (-get-in store [i])))) ;; 2706 msecs

  (<!! (-get-in store [11]))

  (<!! (-assoc-in store ["foo"] nil))
  (<!! (-assoc-in store ["foo"] {:bar {:foo "baz"}}))
  (<!! (-assoc-in store ["foo"] (into {} (map vec (partition 2 (range 1000))))))
  (<!! (-update-in store ["foo" :bar :foo] #(str % "foo")))
  (type (<!! (-get-in store ["foo"])))

  (<!! (-assoc-in store ["baz"] #{1 2 3}))
  (<!! (-assoc-in store ["baz"] (java.util.HashSet. #{1 2 3})))
  (type (<!! (-get-in store ["baz"])))

  (<!! (-assoc-in store ["bar"] (range 10)))
  (.read (<!! (-bget store "bar" :input-stream)))
  (<!! (-update-in store ["bar"] #(conj % 42)))
  (type (<!! (-get-in store ["bar"])))

  (<!! (-assoc-in store ["boz"] [(vec (range 10))]))
  (<!! (-get-in store ["boz"]))



  (<!! (-assoc-in store [:bar] 42))
  (<!! (-update-in store [:bar] inc))
  (<!! (-get-in store [:bar]))

  (import [java.io ByteArrayInputStream ByteArrayOutputStream])
  (let [ba (byte-array (* 10 1024 1024) (byte 42))
        is (io/input-stream ba)]
    (time (<!! (-bassoc store "banana" is))))
  (def foo (<!! (-bget store "banana" identity)))
  (let [ba (ByteArrayOutputStream.)]
    (io/copy (io/input-stream (:input-stream foo)) ba)
    (alength (.toByteArray ba)))

  (<!! (-assoc-in store ["monkey" :bar] (int-array (* 10 1024 1024) (int 42))))
  (<!! (-get-in store ["monkey"]))

  (<!! (-assoc-in store [:bar/foo] 42))

  (defrecord Test [a])
  (<!! (-assoc-in store [42] (Test. 5)))
  (<!! (-get-in store [42]))



  (assoc-in nil [] {:bar "baz"})





  (defrecord Test [t])

  (require '[clojure.java.io :as io])

  (def fsstore (io/file "resources/fsstore-test"))

  (.mkdir fsstore)

  (require '[clojure.reflect :refer [reflect]])
  (require '[clojure.pprint :refer [pprint]])
  (require '[clojure.edn :as edn])

  (import '[java.nio.channels FileLock]
          '[java.nio ByteBuffer]
          '[java.io RandomAccessFile PushbackReader])

  (pprint (reflect fsstore))


  (defn locked-access [f trans-fn]
    (let [raf (RandomAccessFile. f "rw")
          fc (.getChannel raf)
          l (.lock fc)
          res (trans-fn fc)]
      (.release l)
      res))


  ;; doesn't really lock on quick linux check with outside access
  (locked-access (io/file "/tmp/lock2")
                 (fn [fc]
                   (let [ba (byte-array 1024)
                         bf (ByteBuffer/wrap ba)]
                     (Thread/sleep (* 60 1000))
                     (.read fc bf)
                     (String. (java.util.Arrays/copyOf ba (.position bf))))))


  (.createNewFile (io/file "/tmp/lock2"))

  (.renameTo (io/file "/tmp/lock2") (io/file "/tmp/lock-test"))


  (.start (Thread. (fn []
                     (locking "foo"
                       (println "locking foo and sleeping...")
                       (Thread/sleep (* 60 1000))))))

  (locking "foo"
    (println "another lock on foo"))

  (time (doseq [i (range 10000)]
          (spit (str "/tmp/store/" i) (pr-str (range i)))))
  )
