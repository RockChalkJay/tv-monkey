(ns tv-monkey.roku
  (:require [clojure.core.async :as a])
  (:import [java.net DatagramSocket
                     DatagramPacket
                     InetAddress
                     SocketException]))

(def discovery-msg
  (str "M-SEARCH * HTTP/1.1\r\n"
       "Host: 239.255.255.250:1900\r\n"
       "Man: \"ssdp:discover\"\r\n"
       "ST: roku:ecp\r\n"))

(defn- raw->device [raw]
  {:usn (second (re-find #"USN: (.*)" raw))
   :url (second (re-find #"LOCATION: (.*)" raw))})

(defn discovery
  "Blocking fn that sends a SSDP discovery and loops to receive responses.
  The fn will continue to listen for replies until it timesout after
  1000 ms (that seems to be the sweet spot). The results are parsed and
  roku devices are return as a vector of {:usn <roku-uuid> :url <roku-ip>}"
  []
  (let [address (InetAddress/getByName "239.255.255.250")
        sock (DatagramSocket. 1900)
        packet (DatagramPacket. (byte-array 1024) 1024)
        bytes (.getBytes discovery-msg)
        disco-p (DatagramPacket. bytes (count bytes) address 1900)
        c (a/chan)]
    ;;Send M-SEARCH request
    (.send sock disco-p)
    ;;Loop and read responses
    (loop [roku-devices []]
      (a/go
        (do
          (try
            (.receive sock packet)
            (->> (String. (.getData packet) 0 (.getLength packet))
                 (raw->device)
                 (a/>! c))
            (catch SocketException se
              ;;just eat the exception. It's most likely caused by
              ;;closing the socket on timeout
              ))))
      (let [device (first (a/alts!! [c (a/timeout 1000)]))]
        (if (nil? device)
          (do
            (.close sock)
            roku-devices)
          (recur (conj roku-devices device)))))))


