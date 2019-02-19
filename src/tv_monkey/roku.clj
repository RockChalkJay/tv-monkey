(ns tv-monkey.roku
  (:require [clojure.core.async :as a]
            [clj-http.client :as http]
            [clojure.xml :as xml]
            [clojure.string :as str])
  (:import [java.net DatagramSocket
                     DatagramPacket
                     InetAddress
                     SocketException
                     URLEncoder]))

(defn device-info [roku-addr]
  (http/get (str roku-addr "/query/device-info")))

(defn- raw->device [raw]
  (let [addr (second (re-find #"LOCATION: (.*)/" raw))
        device-info-str (str/replace (:body (device-info addr)) #"\t" "")
        device-xml (xml/parse (java.io.ByteArrayInputStream. (.getBytes device-info-str)))]

    (->> (:content device-xml)
         (map #(conj {} {(:tag %) (first (:content %))}))
         (reduce conj {})
         (merge {:url addr}))))

(def discovery-msg
  (str "M-SEARCH * HTTP/1.1\r\n"
       "Host: 239.255.255.250:1900\r\n"
       "Man: \"ssdp:discover\"\r\n"
       "ST: roku:ecp\r\n"))

(defn discover
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

(def special-fn-keys {:home           "Home"
                      :fwd            "Fwd"
                      :rev            "Rev"
                      :select         "Select"
                      :back           "Back"
                      :backspace      "Backspace"
                      :search         "Search"
                      :enter          "Enter"
                      :play           "Play"
                      :left           "Left"
                      :right          "Right"
                      :up             "Up"
                      :down           "Down"
                      :instant-replay "InstantReplay"
                      :volume-up      "VolumeUp"
                      :volume-down    "VolumeDown"
                      :mute           "VolumeMute"
                      :channel-up     "ChannelUp"
                      :channel-down   "ChannelDown"
                      :input-tuner    "InputTuner"
                      :HDMI1          "InputHDMI1"
                      :HDMI2          "InputHDMI2"
                      :HDMI3          "InputHDMI3"
                      :HDMI4          "InputHDMI4"
                      :AV1            "InputAV1"
                      :power-on       "PowerOn"
                      :power-off      "PowerOff"})

(defn key->roku-value [key]
  (if (keyword? key)
    (key special-fn-keys)
    (str "Lit_" (URLEncoder/encode key "UTF-8"))))

(defn press-keys [roku-addr keys-coll]
  (map #(http/post (str roku-addr "/keypress/" (key->roku-value %))) keys-coll))

(defn type-str [roku-addr s]
  (->> (map str (seq s))
       (press-keys roku-addr)))


