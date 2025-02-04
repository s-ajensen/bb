#!/usr/bin/env bb
; kak: filetype=clojure

;; For customisation see vars `targets` and `monitor-specs`
;; Requires babashka >= 0.8.3

; FIXME
; - pass _target to compute and others?
; - use preload for functions common to multiple scripts?
; - throttle overactive monitors?

(require
 '[babashka.fs :as fs]
 '[babashka.process :as p]
 '[clojure.core.async :as as]
 '[clojure.java.io :as io]
 '[clojure.java.shell :refer [sh]]
 '[clojure.set :as set]
 '[clojure.string :as str]
 '[taoensso.timbre :as timbre :refer [debug info warn error spy]])

(import
 'java.time.format.DateTimeFormatter
 'java.time.LocalDateTime
 'sun.misc.Signal
 'sun.misc.SignalHandler)

(def script-name (fs/file-name *file*))

(defn exit [status & msg]
  (when msg
    (.println *err* (str/join " " msg)))
  (System/exit status))

(defn home-path [& path-components]
  (str/join fs/file-separator (concat [(System/getenv "HOME")] path-components)))

(defn cache-path [& path-components]
  (apply home-path (concat [".cache" script-name] path-components)))

(defn truncate [s n]
  (subs s 0 (min (count s) n)))

(defn seconds [n] (* n 1000))

(defn minutes [n] (* n 60 1000))

(defn pad-string* [width s right?]
  (format (str "%" (if right? "-" "") width "s") (truncate s width)))

(defn pad-left [width s]
  (pad-string* width s false))

(defn pad-right [width s]
  (pad-string* width s true))

(defn path-exists? [path] (.exists (io/file path)))

; Returns pid as string
(defn get-current-pid [] (str (.pid (java.lang.ProcessHandle/current))))

(defn start-thread [f] (-> f Thread. .start))

; Returns list of PIDs (as strings) found using pgrep, or []
(defn pgrep [& args]
  (let [argv (concat ["pgrep"] args)
        p (apply sh argv)
        status (:exit p)]
    (case status
      0 (str/split (:out p) #"\n")
      1 []
      (throw (ex-info (str "pgrep " argv " error")
                      p)))))

;; Exception if non-zero exit
(defn sh-or-throw [& argv]
  (let [p (apply sh argv)
        status (:exit p)]
    (if (= status 0)
      p
      (throw (ex-info (str "command " argv " exited")
                      p)))))

;; Returns trimmed stdout for command, exception on failure
(defn sh-out [& argv]
  (let [res (apply sh-or-throw argv)]
    (-> res :out str/trim)))

; Returns :linux or :mac
(def os
  (let [os-name (-> (System/getProperty "os.name") str/lower-case)]
    (cond
      (str/starts-with? os-name "mac") :mac
      (= os-name "linux") :linux
      :else (exit 1 (str "unsupported os: " os-name)))))

(def targets
  {"dwm" {:publisher (partial sh-or-throw "xsetroot" "-name")}

   ;; my tmux.conf has:
   ;;   set -g status-interval 1
   ;;   set -g status-right "#(cat ~/.tmux-bar)"
   ;;   set -g status-right-length 200
   "tmux" {:publisher (partial spit (home-path ".tmux-bar"))
           :blink-time (seconds 1)}

   "stdout" {:publisher println}

   ;; useful for debugging this script
   "debug" {:publisher #(info "bar=" %)
            :log-level :debug
            :log-to-stdout true}})

; We make dirs for all targets, as they will be used when triggering
(doseq [target (keys targets)]
  (fs/create-dirs (cache-path target)))

(defn log-path [target]
  (cache-path (str target ".log")))

(defn setup-logging [target]
  (let [target-spec (get targets target)
        level (get target-spec :log-level :info)
        to-stdout (:log-to-stdout target-spec)
        file (log-path target)]
    (when-not to-stdout
      (println "logging to" file)
      (spit file "\n" :append true)) ; check it's writable
    (timbre/merge-config!
     {:appenders (if to-stdout
                   {}
                   {:println nil
                    :spit (timbre/spit-appender {:fname file})})
      :output-fn #(str (-> % :timestamp_ force) " "
                       (->> % :level name str/upper-case (pad-right 5)) " "
                       (force (:msg_ %)))
      :min-level level
      :timestamp-opts {:pattern "yyyy/MM/dd HH:mm:ss.SSS"}})))

;; For storing monitor results, maps monitor id to its last message
(def results (atom {}))

;; Channel to push monitor results to, as [monitor-id message],
;; eg [:volume "50%"] or [:wifi nil]
(def results-chan (as/chan))

(defn push-result [id s]
  (as/>!! results-chan [id s]))

(defn run-handling-lines
  "Runs the command, passing each stdout/stderr line to the relevant handler.
   Returns the process exit code."
  [command handle-stdout handle-stderr]

  (let [proc (p/process command {:shutdown p/destroy-tree})
        reader->channel (fn [r]
                          (let [c (as/chan)]
                            (start-thread #(binding [*in* r]
                                             (loop [line (read-line)]
                                               (when (not (nil? line))
                                                 (as/>!! c line)
                                                 (recur (read-line))))
                                             (as/>!! c :exit)))
                            c))]

    (with-open [reader-stdout (io/reader (:out proc))
                reader-stderr (io/reader (:err proc))]
      (let [stdout-chan (reader->channel reader-stdout)
            stderr-chan (reader->channel reader-stderr)]
        (loop []
          (let [[line c] (as/alts!! [stdout-chan stderr-chan])
                handler (condp = c
                          stdout-chan handle-stdout
                          stderr-chan handle-stderr)]
            (when (not= line :exit)
              (handler line)
              (recur))))))
    (:exit @proc)))

(defn emit-from-command
  "Reads lines from the command's stdout, adds the label then
   pushes to the results channel.
   If a line is empty, pushes nil.
   If the command exits, pushes \"EXIT\"."
  [id label command]
  (let [log-prefix (str (name id) " monitor:")
        handle-stdout (fn [line]
                        (let [msg (when-not (empty? line)
                                    (str (or label "") line))]
                          (push-result id msg)))
        handle-stderr (fn [line]
                        (warn log-prefix line))]
    (try
      (let [exit-status (run-handling-lines command handle-stdout handle-stderr)]
        (warn log-prefix "process exited with status" exit-status))
      (catch Exception e
        (warn log-prefix "error starting command:" e)))
    (push-result id (str (or label (str id " "))
                         "EXIT"))))

(defn emit-from-function
  "Calls the compute function, handles errors then adds the
  label and pushes to the results channel.
  If compute throws, logs exception and pushes \"BUG\"."
  [id label compute]
  (let [computed (try
                   (compute)
                   (catch Exception e
                     (warn "exception:" (ex-message e) (ex-data e))
                     (str (when (empty? label)
                            (str id " "))
                          "BUG")))
        computed (if (empty? computed) nil computed)
        msg (if (empty? computed)
              nil
              (str label computed))]
    (push-result id msg)))

(defn make-monitor
  "Validates params and returns a map describing the monitor"
  [& {:keys [id label interval compute thread command]}]
  (cond
    (and id label interval compute (not (or thread command)))
    (let [emitter #(emit-from-function id label compute)
          trigger-chan (as/chan)]
      {:id id
       :trigger-chan trigger-chan
       :thread (fn [_target]
                 (while true
                   #_(debug "emitting" id)
                   (emitter)
                   (as/alts!! [(as/timeout interval)
                               trigger-chan])))})

    (and id command label (not (or interval compute thread)))
    {:id id
     :thread (fn [_target]
               (emit-from-command id label command))}

    (and id thread (not (or label interval compute command)))
    {:id id
     :thread thread}

    :else
    (throw (Exception. (str "make-monitor: invalid combination of params for monitor with id "
                            (or id "(unknown)"))))))

(def monitor-specs
  "The order in the list defines the visual order, from right to left.
   For comfort, better to have the monitors changing frequently on
   the left, so at the end of this list."

  (let [time-formatter (DateTimeFormatter/ofPattern "E dd MMM HH:mm:ss")
        interface (try
                    (let [matches (fs/glob "/sys/class/net" "wl*")]
                      (if (and matches (> (count matches) 0))
                        (-> matches first fs/file-name)
                        (warn "cannot identify wifi interface")))
                    (catch Exception _
                      nil))
        temp-path "/sys/class/thermal/thermal_zone0/temp"

        ; For the :alert monitor
        alert-chan (as/chan)
        add-alert #(as/go (as/>!! alert-chan [:add %]))
        remove-alert #(as/go (as/>!! alert-chan [:remove %]))]

    [; A monitor showing blinking messages. Used by my battery monitor for making
     ; it more obvious when I need to charge.
     ; Alerts are added/removed via functions add-alert/remove-alert above.
     ; Again I went over the top with this one...
     {:id :alert
      :thread (fn [target]
                ; Listens on alert-chan, blinking done by a specialised thread
                (let [alerts (atom #{})
                      blink-time (get-in targets [target :blink-time] 500)
                      idle-time (minutes 10)
                      blinker-chan (as/chan)
                      ; Reads blinker-chan for messages of the form [s1, s2], and
                      ; will have the :alert monitor display both strings in turn.
                      ; FIXME yucky
                      blinker #(loop [delay idle-time
                                      s1 nil
                                      s2 nil]
                                 (let [[msg c] (as/alts!! [(as/timeout delay)
                                                           blinker-chan])]
                                   (if (= c blinker-chan)
                                     ; got a message
                                     (let [[s1 s2] msg]
                                       (push-result :alert s1)
                                       (recur (if (and (nil? s1) (nil? s2))
                                                idle-time
                                                0)
                                              s1 s2))
                                     ; got a timeout
                                     (if (and (nil? s1) (nil? s2))
                                       (recur idle-time nil nil)
                                       (do (push-result :alert s1)
                                           (recur blink-time s2 s1))))))]
                  (start-thread blinker)
                  (loop [[op s] (as/<!! alert-chan)]
                    (let [old-count (count @alerts)]
                      (case op
                        :add (swap! alerts conj s)
                        :remove (swap! alerts disj s))
                      (when (not= (count @alerts) old-count)
                        (if (empty? @alerts)
                          (as/>!! blinker-chan [nil nil])
                          (let [on-s (str "*** " (str/join ", " @alerts) " ***")
                                off-s (apply str (repeat (count on-s) \space))]
                            (as/>!! blinker-chan [on-s off-s])))))
                    (recur (as/<!! alert-chan)))))}

     ; Test for :alert
     {:id :alert-test
      :enabled? false
      :label "test: "
      :interval (seconds 2)
      :compute #(let [present? (path-exists? "/tmp/alert-test")]
                  (if present?
                    (add-alert "present")
                    (remove-alert "present"))
                  nil)}

     ; Test child process stderr capture
     {:id :stderr-test
      :enabled? false
      :label "test: "
      :command ["bash"
                "-c"
                "for i in $(seq 2); do echo stdout $i; echo stderr $i 1>&2; sleep 2; done; echo quitting; exit 6"]}

     {:id :time-with-compute
      :label ""
      :interval (seconds 1)
      :compute #(.format (LocalDateTime/now) time-formatter)}
     ; Same time monitor, with :command
     {:id :time-with-command
      :enabled? false
      :label "time: "
      :command ["bash" "-c" "while :; do date; sleep 1; done"]}
     ; Same time monitor, with :thread
     ; There is no label parameter here, the thread must add it itself
     {:id :time-with-thread
      :enabled? false
      :thread (fn [_target]
                (while true
                  (push-result :time-with-thread
                               (str "time: " (.format (LocalDateTime/now) time-formatter)))
                  (Thread/sleep 1000)))}

     {:id :volume
      :enabled? true
      :label "vol: "
      :interval (seconds 10)
      :compute #(sh-out "bash" "-c" "volume get")}

     {:id :brightness
      :enabled? (path-exists? (home-path "bin/laptop"))
      :label "lcd: "
      :interval (seconds 60)
      :compute #(str (sh-out "laptop" "brightness" "percent")
                     "%")}

     {:id :profile
      :enabled? (path-exists? (home-path "bin/laptop"))
      :label "prof: "
      :interval (seconds 60)
      :compute #(let [out (sh-out "laptop" "profile" "adjust")]
                  (when-not (= out "ok")
                    out))}

     {:id :battery
      :enabled? (path-exists? "/usr/bin/acpi")
      :label "batt: "
      :interval (seconds 10)
      :compute #(let [alert-threshold 15
                      off-threshold 5
                      s (sh-out "acpi" "-b")
                      ;; Acpi output samples:
                      ;;   Battery 0: Charging, 85%, 00:15:35 until charged
                      ;;   Battery 0: Discharging, 85%, 07:55:04 remaining
                      ;;   Battery 0: Full, 100%
                      matches (re-find #"Battery 0:\s+(\w+), (\d+)%(, +(\d\d:\d\d)(?::\d\d))?.*" s)
                      [_ status percent _ duration] matches
                      percent (Integer/parseInt percent)]
                  (if (= status "Discharging")
                    (if (< percent off-threshold)
                      (sh-or-throw "sudo" "poweroff")
                      (when (< percent alert-threshold)
                        (add-alert "power")))
                    (remove-alert "power"))
                  (when (not= status "Full")
                    (str percent
                         "% "
                         (case status
                           "Charging" "C"
                           "Discharging" "D")
                         " "
                         duration)))}

     {:id :temperature
      :enabled? (path-exists? temp-path)
      :label "temp: "
      :interval (seconds 60)
      :compute #(let [threshold 55
                      temp (-> temp-path slurp str/trim Integer/parseInt (/ 1000))]
                  (when (> temp threshold)
                    (str temp "C")))}

     {:id :arch
      :enabled? (path-exists? "/usr/bin/pacman")
      :label "arch: "
      :interval (minutes 60)
      :compute #(let [threshold 100
                      pending (-> (sh-out "arch" "pending")
                                  Integer/parseInt)]
                  (when (> pending threshold)
                    (str pending " updates")))}

     {:id :secrets
      :enabled? (path-exists? (home-path ".secrets"))
      :label "secrets: "
      :interval (minutes 10)
      :compute #(let [out (sh-out "secrets" "status")]
                  (when (not= out "ok")
                    out))}

     {:id :security
      :enabled? (path-exists? (home-path "bin/security"))
      :label "sec: "
      :interval (minutes 10)
      :compute #(let [out (sh-out "security" "-q")]
                  (when (not= out "ok")
                    out))}

     {:id :cloud
      :enabled? (path-exists? (home-path "bin/gcloud2"))
      :label "cloud: "
      :interval (minutes 5)
      :compute #(let [out (sh-out "gcloud2" "status")]
                  (when (not= out "ok")
                    out))}

     {:id :vpn
      :enabled? (path-exists? (home-path "bin/vpn"))
      :label "vpn: "
      :interval (seconds 60)
      :compute #(let [out (sh-out "vpn" "status")]
                  (when (not= out "off")
                    out))}

     ; Shows what's mounted under /mnt
     {:id :mounts
      :enabled? (path-exists? "/proc/mounts")
      :label "mnt: "
      :interval (seconds 30)
      :compute (fn []
                 (let [lines (str/split (slurp (java.io.FileReader. "/proc/mounts")) #"\n")
                       mount-points (map #(second (re-matches #"^[^\s]+\s+([^\s]+)\s.*" %))
                                         lines)
                       mine (filter #(str/starts-with? % "/mnt/")
                                    mount-points)
                       names (map fs/file-name mine)]
                   (str/join ", " names)))}

     {:id :git
      :enabled? (path-exists? (home-path "bin/git.clj"))
      :label "git: "
      :interval (minutes 60)
      :compute #(let [out (sh-out "git.clj" "status")]
                  (when-not (= out "ok")
                    out))}

     ; Shows down status, low signal, SSID if not at home.
     {:id :wifi
      :enabled? (and interface (path-exists? "/usr/bin/iw"))
      :label "wifi: "
      :interval (seconds 10)
      :compute #(let [quality-threshold 30
                      home-ssid "BTHub3-5NGQ"

                      out (sh-out "iw" "dev" interface "link")
                      matches (re-matches #"(?s).*SSID:\s+([^\s]+)\s.*signal:\s+-(\d+) dBm.*"
                                          out)]
                  (cond
                    matches (let [[_ ssid signal-s] matches
                                  not-home? (not= ssid home-ssid)
                                  signal (Integer/parseInt signal-s)
                                  quality (int (* (- 90 signal) (/ 100 60)))]
                              (when (or not-home? (< quality quality-threshold))
                                (str (if not-home? (str ssid " ") "")
                                     quality "%")))

                    (= out "Not connected.") "down"

                    :else (do
                            (warn "wifi monitor: unexpected iw output:" out)
                            "BUG")))}

     {:id :vm
      :enabled? (path-exists? "/usr/bin/virsh")
      :label ""
      :interval (minutes 1)
      :compute #(let [cmd ["virsh" "list" "--state-running" "--name"]
                      lines (-> (apply sh-out cmd) (str/split #"\n"))
                      non-empty-lines (filter seq
                                              lines)
                      machines (str/join " " non-empty-lines)]
                  (when (seq non-empty-lines)
                    machines))}

     {:id :disk
      :enabled? (or (path-exists? "/bin/df") (path-exists? "/usr/bin/df"))
      :label "disk: "
      :interval (minutes 5)
      :compute #(let [threshold 50
                      out (sh-out "df" "/")
                      percent (-> (re-find #"(\d+)%" out)
                                  second
                                  Integer/parseInt)]
                  (when (>= percent threshold)
                    (str percent "%")))}

     {:id :memory
      :enabled? (path-exists? "/proc/meminfo")
      :label "mem: "
      :interval (seconds 30)
      :compute #(let [threshold 50
                      meminfo (slurp (java.io.FileReader. "/proc/meminfo"))
                      matches (re-matches #"(?s).*MemTotal:\s+(\d+)\s.*MemAvailable:\s+(\d+)\s.*"
                                          meminfo)
                      [_ total-s available-s] matches
                      total (Integer/parseInt total-s)
                      available (Integer/parseInt available-s)
                      percent (int (* (/ (- total available) total) 100))]
                  (when (>= percent threshold)
                    (str percent "%")))}

     (let [total-cpu-threshold 30
           sampling-period 5]
       {:id :cpu
        :enabled? (or (and (= os :linux) (path-exists? "/usr/bin/pidstat"))
                      (= os :darwin))
        :label "cpu: "
        :command ["status-bar-cpu"
                  sampling-period
                  total-cpu-threshold]})

     {:id :dnsmasq
      :enabled? (path-exists? "/usr/bin/dnsmasq")
      :label "dnsmasq: "
      :command ["status-bar-dnsmasq.clj"]}]))

(defn make-monitors
  "From `monitor-specs` creates monitors.
   To help during development, if the `only` list of monitor ids is given,
   only these monitors will be enabled, whatever the value of their :enabled?
   property.
   We exit if `monitor-specs` contains duplicated IDs or `only` is invalid."
  [& {:keys [only]}]

  (let [duplicate-ids (->> (map :id monitor-specs)
                           (frequencies)
                           (filter (fn [[_id n]] (> n 1)))
                           (map first))
        _ (when (seq duplicate-ids)
            (exit 2 (str "duplicate monitor ids in monitor-specs: "
                         (str/join ", " duplicate-ids))))

        valid-ids (set (map :id monitor-specs))
        requested-ids (set only)
        invalid-ids (set/difference requested-ids valid-ids)
        _ (when (seq invalid-ids)
            (exit 2 (str "aborting - unknown monitors: " (str/join " " invalid-ids))))

        active-specs (if (seq only)
                       (filter #(some #{(-> % :id)} requested-ids)
                               monitor-specs)
                       (filter #(if (contains? % :enabled?)
                                  (:enabled? %)
                                  true)
                               monitor-specs))
        monitors (map #(apply make-monitor (apply concat %))
                      active-specs)]

    (info "enabled monitors:" (vec (map :id monitors)))
    (when-not (seq monitors)
      (exit 1 "no monitors"))
    monitors))

(defn start-monitoring [target monitors]
  ;; Start monitor threads
  (doseq [monitor monitors]
    (let [thread (:thread monitor)]
      (start-thread #(thread target))))

  ; Listen for triggers
  (let [monitor-by-id (fn [id]
                        (first (filter #(= (:id %) id)
                                       monitors)))
        trigger-monitor (fn [id]
                          (if-let [monitor (monitor-by-id id)]
                            (if-let [trigger-chan (:trigger-chan monitor)]
                              (as/>!! trigger-chan true)
                              (warn "trigger-monitor:" (name id) "monitor does not support triggers"))
                            (warn "trigger-monitor: no monitor with id" id)))
        watch-for-triggers (fn []
                             (while true
                               (let [paths (map str (fs/list-dir (cache-path target)))
                                     files (filter (complement fs/directory?) paths)]
                                 (doseq [path files]
                                   (let [id (fs/file-name path)]
                                     (info "trigger:" id)
                                     (fs/delete-if-exists path)
                                     (trigger-monitor (keyword id)))))
                               (Thread/sleep 200)))]
    (start-thread watch-for-triggers))

  ;; Consume monitor results
  (let [publisher (get-in targets [target :publisher])
        assemble-bar (fn []
                       (let [ids (reverse (map :id monitors))
                             values (map #(let [v (get @results %)]
                                            (when v (truncate v 40)))
                                         ids)
                             visible (filter boolean values)]
                         (str " " (str/join " | " visible) " ")))]
    (loop [msg (as/<!! results-chan)]
      (debug "consuming" msg)
      (let [[id line] msg]
        (swap! results assoc id line)
        (try
          (publisher (assemble-bar))
          (catch Exception e
            (error "error invoking publisher, exiting:" (ex-message e))
            (exit 1 "error invoking publisher, exiting"))))
      (recur (as/<!! results-chan)))
    (while true (Thread/sleep 1000))))

(defn trigger-externally
  "Creates files in directories watched by status-bar processes"
  [requested-ids]
  (let [existing-ids (map (comp name :id) monitor-specs)]
    (doseq [requested-id requested-ids]
      (if (some #{requested-id} existing-ids)
        (doseq [target (keys targets)]
          (let [path (cache-path target requested-id)]
            (io/make-parents path)
            (spit path "")))
        (println (str script-name ": unknown monitor: " requested-id))))))

(let [command (first *command-line-args*)
      args (next *command-line-args*)

      usage (fn []
              (let [monitor-names (map (comp name :id) monitor-specs)]
                (exit 2
                      (str "Usage: " script-name " run TARGET\n"
                           "   or: " script-name " run TARGET MONITOR ...\n"
                           "       Can be used during development to only start specific monitors,\n"
                           "       whatever the value of their `enabled?`` property.\n"
                           "   or: " script-name " log TARGET [COMMAND ...]\n"
                           "       View the log file using less (or the specified command).\n"
                           "   or: " script-name " trigger MONITOR ...\n"
                           "       If the monitor has a `compute` function, requests an immediate update.\n"
                           "\n"
                           "Targets:  " (str/join " " (keys targets)) "\n"
                           "Monitors: " (str/join " " monitor-names)))))

      run (fn []
            (let [[target & requested-monitors] args
                  requested-monitors-ids (when (seq? requested-monitors)
                                           (map keyword requested-monitors))
                  ; Log sigint, so cause of some warnings in logs is obvious
                  handle-sigint #(. Signal
                                    (handle (Signal. "INT")
                                            (reify SignalHandler
                                              (handle [_ _]
                                                (warn "received SIGINT, exiting")
                                                (exit 1)))))

                  kill-other-instances
                  (fn []
                    (let [my-pid (get-current-pid)
                          process-re (str "bb(-0.8.3)? +([^ ]+/)?" script-name " +run +" target "( .*)?$")
                          matches (pgrep "-u" (System/getenv "USER") "-f" process-re)
                          other-pids (filter #(not= % my-pid) matches)]
                      (when-not (empty? other-pids)
                        (apply sh-or-throw (vec (concat ["kill" "-2" "--"] other-pids))))))]

              (when-not (contains? targets target)
                (usage))
              (setup-logging target)
              (info "starting")
              (handle-sigint)
              (kill-other-instances)
              (let [monitors (make-monitors :only requested-monitors-ids)]
                (start-monitoring target monitors))))

      trigger (fn []
                (when (zero? (count args))
                  (usage))
                (let [monitor-ids args]
                  (trigger-externally monitor-ids)))

      view-log (fn []
                 (let [[target & command] args
                       valid-target? (boolean (get targets target))]
                   (when-not valid-target?
                     (usage))
                   (let [log (log-path target)
                         command (or command ["less" "-S" "+F"])
                         argv (concat command [log])]
                     (p/exec argv)
                     nil)))]
  (case command
    "run" (run)
    "trigger" (trigger)
    "log" (view-log)
    (usage)))
