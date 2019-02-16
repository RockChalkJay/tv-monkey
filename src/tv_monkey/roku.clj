(ns tv-monkey.roku
  (:require [clojure.core.async :as a]
            [clj-http.client :as http]
            [clojure.xml :as xml]
            [clojure.string :as str])
  (:import [java.net DatagramSocket
                     DatagramPacket
                     InetAddress
                     SocketException]))

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

(def remote-keys
  {
   :a "Lit_a" :b "Lit_b" :c "Lit_c" :d "Lit_d" :e "Lit_e" :f "Lit_f" :g "Lit_g"
   :h "Lit_h" :i "Lit_i" :j "Lit_j" :k "Lit_k" :l "Lit_l" :m "Lit_m" :n "Lit_n"
   :o "Lit_o" :p "Lit_p" :q "Lit_q" :r "Lit_r" :s "Lit_s" :t "Lit_t" :u "Lit_u"
   :v "Lit_v" :w "Lit_w" :x "Lit_x" :y "Lit_y" :z "Lit_z"

   :A "Lit_A" :B "Lit_B" :C "Lit_C" :D "Lit_D" :E "Lit_E" :F "Lit_F" :G "Lit_G"
   :H "Lit_H" :I "Lit_I" :J "Lit_J" :K "Lit_K" :L "Lit_L" :M "Lit_M" :N "Lit_N"
   :O "Lit_O" :P "Lit_P" :Q "Lit_Q" :R "Lit_R" :S "Lit_S" :T "Lit_T" :U "Lit_U"
   :V "Lit_V" :W "Lit_W" :X "Lit_X" :Y "Lit_Y" :Z "Lit_Z"

   :home "home" :select "select" :back "back" :enter "enter" :play "play"
   :left "left" :right "right" :up "up" :down "down" :volume-up "volumeup"
   :volume-down "volumedown" :mute "volumemute" :power-off "poweroff"
   })

(defn press-keys [roku-addr keys]
  (map #(http/post (str roku-addr "/keypress/" ((keyword %) remote-keys))) keys))

(defn type-str [roku-addr s]
  (->> (seq s)
       (map #((keyword (str %)) remote-keys))
       (map #(http/post (str roku-addr "/keypress/" %)))))


