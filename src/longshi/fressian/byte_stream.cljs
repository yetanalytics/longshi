(ns longshi.fressian.byte-stream
  "Byte input and output streams along with checksum"
  (:require [longshi.fressian.byte-stream-protocols :as bsp]
            [longshi.fressian.protocols :as p]
            [longshi.fressian.handlers :as fh]
            [longshi.fressian.hop-map :as hm]
            [longshi.fressian.codes :as c]
            [longshi.fressian.utils :refer [make-byte-array make-data-view little-endian]]))

(defn adler32
  "Adler32 checksum algorithm for bytestreams

  ba ([bytes]) - Array of bytes to get the checksum for
  adler (int) - Checksum value (defaults to 1)"
  ([ba] (adler32 ba 1))
  ([ba adler]
    (let [ba-len (alength ba)
          sums (js/Uint32Array. 2)]
      (aset sums (bit-and adler 0xffff) 0)
      (aset sums (bit-and (unsigned-bit-shift-right adler 16) 0xffff) 1)
      (loop [i 0 adler-chunk 0]
        (when (< i ba-len)
          (aset sums 0 (+ (aget ba i) (aget sums 0)))
          (aset sums 1 (+ (aget sums 0) (aget sums 1)))
          (if (== adler-chunk 5552)
            (do
              (aset sums 0 (js-mod (aget sums 0) 65521))
              (aset sums 1 (js-mod (aget sums 1) 65521))
              (recur (inc i) 0))
            (recur (inc i) (inc adler-chunk)))))
      (aset sums 0 (js-mod (aget sums 0) 65521))
      (aset sums 1 (js-mod (aget sums 1) 65521))
      (unsigned-bit-shift-right (bit-or (bit-shift-left (aget sums 1) 16) (aget sums 0)) 0))))

;;Integer buffer
(def ^:private i32a (make-byte-array 4))
(def ^:private i32adv (make-data-view i32a))
;;Double buffer
(def ^:private da (make-byte-array 8))
(def ^:private dadv (make-data-view da))

(deftype
  ^{:doc
    "Output bytestream for fressian writer

    stream ([bytes]) - Byte array to write to.  Double in size when full
    cnt (int) - The total number of bytes written for the stream
    checkpoint (int) - The starting pointer to the current fressian bytestream
    handlers (ILookup) - Map for looking up a values handler
    struct-cache (InterleavedIndexHopMap) - Cache for the names of value types
    priority-cache (InterleavedIndexHopMap) - Cache for values written"}
  ByteOutputStream [^:mutable stream ^:mutable cnt ^:mutable checkpoint handlers ^:mutable struct-cache ^:mutable priority-cache]
  Object
  (clear-caches! [bos]
    "Clears the type and value caches"
    (set! struct-cache (hm/interleaved-index-hop-map 16))
    (set! priority-cache (hm/interleaved-index-hop-map 16)))
  bsp/ByteBuffer
  (get-bytes [bos]
    stream)
  (duplicate-bytes [bos]
    (let [new-stream (make-byte-array cnt)]
      (.set new-stream (.subarray stream 0 cnt))
      new-stream))
  bsp/CheckedStream
  (get-checksum [bos]
    (adler32 (.subarray stream checkpoint cnt)))
  bsp/ResetStream
  (reset! [bos]
    (set! checkpoint cnt))
  bsp/RawWriteStream
  (bytes-written [bos]
    (- cnt checkpoint))
  bsp/WriteStream
  (write! [bos b]
    (let [new-count (inc cnt)]
      (if (< (alength stream) new-count)
        (let [new-stream (make-byte-array (max new-count (bit-shift-left cnt 1)))]
          (.set new-stream stream)
          (set! stream new-stream)))
      (aset stream cnt b)
      (set! cnt new-count)))
  (write-bytes! [bos b off len]
    (let [new-count (+ cnt len)]
      (if (< (alength stream) new-count)
        (let [new-stream (make-byte-array (max new-count (bit-shift-left cnt 1)))]
          (.set new-stream stream)
          (set! stream new-stream)))
      (.set stream (.subarray b off (+ off len)) cnt)
      (set! cnt new-count)))
  bsp/IntegerWriteStream
  (write-int16! [bos i16]
    (.setInt16 i32adv 0 i16 little-endian)
    (bsp/write-bytes! bos i32a 0 2))
  (write-int24! [bos i24]
    (.setInt32 i32adv 0 i24 little-endian)
    (bsp/write-bytes! bos i32a 0 3))
  (write-int32! [bos i32]
    (.setInt32 i32adv 0 i32 little-endian)
    (bsp/write-bytes! bos i32a 0 4))
  (write-unsigned-int16! [bos ui16]
    (.setUint16 i32adv 0 ui16 little-endian)
    (bsp/write-bytes! bos i32a 0 2))
  (write-unsigned-int24! [bos ui24]
    (.setUint32 i32adv 0 ui24 little-endian)
    (bsp/write-bytes! bos i32a 0 3))
  (write-unsigned-int32! [bos ui32]
    (.setUint32 i32adv 0 ui32 little-endian)
    (bsp/write-bytes! bos i32a 0 4))
  bsp/FloatWriteStream
  (write-float! [bos f]
    (.setFloat32 dadv 0 f little-endian)
    (bsp/write-bytes! bos da 0 4))
  bsp/DoubleWriteStream
  (write-double! [bos d]
    (.setFloat64 dadv 0 d little-endian)
    (bsp/write-bytes! bos da 0 8))
  bsp/SeekStream
  (seek! [bos pos]
      (when (< cnt pos)
        (throw (js/Error. (str "Tried to seek to (" pos ").  Stream is of size (" cnt ")"))))
       (set! cnt pos))
  ICounted
  (-count [bos] cnt))

(defn byte-output-stream
  "Creates a fressian output bytestream

  len (int) - The starting length of the internal byte array (defaults to 32)
  user-handlers (ILookup) - User defined handlers for writing values (defaults to empty lookup)"
  ([] (byte-output-stream 32))
  ([len] (byte-output-stream 32 #js {}))
  ([len user-handlers]
   (->ByteOutputStream
    (make-byte-array len)
    0
    0
    (fh/write-lookup fh/core-write-handlers user-handlers fh/extended-write-handlers)
    (hm/interleaved-index-hop-map 16)
    (hm/interleaved-index-hop-map 16))))

(deftype
  ^{:doc
    "Cache for the structure of types
    tag (string) - The name of the values type
    fields (int) - The number of fields the value has"}
  StructCache [tag fields])
(defn struct-cache
  "Constructor for StructCache"
  [tag fields]
  (StructCache. tag fields))
;;Marker object for when reader is constrcuting a value
(def under-construction #js {})

(deftype
  ^{:doc
    "Input bytestream for fressian reader

    stream ([bytes]) - Byte array to read from.
    cnt (int) - The total number of bytes read from the stream
    checkpoint (int) - The starting pointer to the current fressian bytestream
    handlers (ILookup) - User defined map for looking up a values handler
    standard-handlers (ILookup) - Standard map for looking up a values handler
    struct-cache ([StructCache]) - Cache for the names of value types
    priority-cache ([Object]) - Cache for values written
    use-checksum (bool) - Flag for valdiating the footer checksum" }
  ByteInputStream [^:mutable stream ^:mutable cnt ^:mutable checkpoint handlers standard-handlers ^:mutable struct-cache  ^:mutable priority-cache use-checksum]
  Object
  (clear-caches! [bos]
    "Clears the type and value caches"
    (set! struct-cache #js [])
    (set! priority-cache #js []))
  (handle-struct [bis tag fields]
    "Constructs a value for a extended or custom value

     tag (string) - Name of the handler to use for construction
     fields (int) - The number of fields to read to constuct the value

     If no handler is found a tagged object is returned"
    (let [rh (or (get handlers tag) (get standard-handlers tag))]
      (if-not (nil? rh)
        (rh bis tag fields)
        (let [values (make-array fields)]
          (dotimes [i fields]
            (aset values i (p/read-object! bis)))
          (fh/tagged-object tag values)))))
  (lookup-cache [bis cache index]
    "Looks up item in cache

     cache (Array) - Cache to search for item
     index (int) - Location of cached item"
    (if (< index (alength cache))
      (let [result (aget cache index)]
        (if (identical? under-construction result)
          (throw (js/Error. "Unable to resolve circular refernce in cache"))
          result))
       (throw (js/Error. "Requested object beyond end of cache: (" index ")"))))
  (read-and-cache-object [bis cache]
    "Reads and caches an object

     cache (Array) - Cache the item will be put into"
    (let [index (alength cache)]
      (.push cache under-construction)
      (let [o (p/read-object! bis)]
        (aset cache index o)
        o)))
  (validate-footer! [bis calculated-length magic-from-string]
    "Validates the footer

     calculated-length (int) - The length gotten from the footer
     magic-from-string (int) - The magic footer code

     The validations done are checking the footer code, toe fressian bytestream length,
     and optionally checking the checksum"
    (let [valid-magic (== magic-from-string (.-FOOTER_MAGIC c/codes))
          stream-length (if valid-magic (bsp/read-int32! bis) -1)
          valid-length (and valid-magic (== stream-length calculated-length))
          checksum (if (and valid-length use-checksum) (bsp/get-checksum bis) -1)
          stream-checksum (if (and valid-length use-checksum) (bsp/read-unsigned-int32! bis) -1)
          valid-checksum (if (and valid-length use-checksum) (== stream-checksum checksum) true)]
      (cond
        (not valid-magic)
        (throw (js/Error. (str "Invalid footer magic expected (" (.-FOOTER_MAGIC c/codes) ") and got (" magic-from-string ")")))
        (not valid-length)
        (throw (js/Error. (str "Invalid footer length expected (" stream-length ") and got (" calculated-length ")")))
        (not valid-checksum)
        (throw (js/Error. (str "Invalid footer checksum expected (" stream-checksum ") and got (" checksum ")"))))))
  (peek-read [bos]
    "Reads the next byte without changing the pointer"
    (when-not (< (alength stream) cnt)
      (aget stream cnt)))
  bsp/ByteBuffer
  (get-bytes [bis]
    stream)
  (duplicate-bytes [bis]
    (let [new-stream (make-byte-array cnt)]
      (.set new-stream (.subarray stream 0 cnt))
      new-stream))
  bsp/CheckedStream
  (get-checksum [bos]
    (adler32 (.subarray stream checkpoint cnt)))
  bsp/ResetStream
  (reset! [bos]
    (set! checkpoint cnt))
  bsp/RawReadStream
  (bytes-read [bos]
    (- cnt checkpoint))
  bsp/ReadStream
  (read! [bis]
    (let [old-count cnt]
      (if (< (alength stream) cnt)
        (throw (js/Error. "Can not read (1) bytes, Input Stream only has (0) bytes available"))
        (set! cnt (inc cnt)))
      (aget stream old-count)))
  (read-bytes! [bis b off len]
    (let [old-count cnt]
      (if (< (alength stream) (+ cnt len off))
        (throw (js/Error. (str "Can not read (" len ") bytes at offset (" off "), Input Stream only has (" (bsp/available bis)") bytes available")))
        (set! cnt (+ cnt off len)))
      (.set b (.subarray stream (+ old-count off) (+ old-count off len)))))
  (available [bis] (max 0 (- (alength stream) cnt)))
  bsp/IntegerReadStream
  (read-int16! [bis]
    (bsp/read-bytes! bis i32a 0 2)
    (.getInt16 i32adv 0 little-endian))
  (read-int24! [bis]
    (bsp/read-bytes! bis (.subarray i32a 1 4) 0 3)
    (aset i32a 0 0)
    (.getInt32 i32adv 0 little-endian))
  (read-int32! [bis]
    (bsp/read-bytes! bis i32a 0 4)
    (.getInt32 i32adv 0 little-endian))
  (read-unsigned-int16! [bis]
    (bsp/read-bytes! bis i32a 0 2)
    (.getUint16 i32adv 0 little-endian))
  (read-unsigned-int24! [bis]
    (bsp/read-bytes! bis (.subarray i32a 1 4) 0 3)
    (aset i32a 0 0)
    (.getUint32 i32adv 0 little-endian))
  (read-unsigned-int32! [bis]
    (bsp/read-bytes! bis i32a 0 4)
    (.getUint32 i32adv 0 little-endian))
  bsp/FloatReadStream
  (read-float! [bis]
    (bsp/read-bytes! bis da 0 4)
    (.getFloat32 dadv 0 little-endian))
  bsp/DoubleReadStream
  (read-double! [bis]
    (bsp/read-bytes! bis da 0 8)
    (.getFloat64 dadv 0 little-endian))
  bsp/SeekStream
  (seek! [bis pos]
    (when (< (alength stream) pos)
      (throw (js/Error. (str "Tried to seek to (" pos ").  Stream is of size (" (alength stream) ")"))))
    (set! cnt pos))
  ICounted
  (-count [bis] (alength stream)))

(defn byte-input-stream
  "Creates a fressian input bytestream

  stream ([byte]) - Byte array to read the fressian datas from
  user-handlers (ILookup) - User defined read handlers (defaults to empty lookup)
  use-checksum (bool) - Validate the checksum when validating footer (defaults to true)"
  ([stream] (byte-input-stream stream #js {}))
  ([stream user-handlers] (byte-input-stream stream user-handlers true))
  ([stream user-handlers use-checksum] (ByteInputStream. stream 0 0 user-handlers fh/core-read-handlers #js [] #js [] use-checksum)))
