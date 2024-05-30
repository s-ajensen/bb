#!/usr/bin/env bb

(require '[babashka.cli :as cli]
         '[clojure.java.shell :refer [sh]]
         '[clojure.string :as str])

(def cmd (first *command-line-args*))
(def step 5)

(defn prx [x]
  (prn x)
  x)

(defn get-volume []
  (let [sinks (:out (sh "bash" "-c" "pactl list sinks"))
        volume-ln (first (filter #(str/includes? % "Volume: ") (str/split sinks #"\n")))]
    (-> (str/split volume-ln #" ")
        (nth 5)
        print)))

(case cmd
  "raise" (sh "bash" "-c" (str "pactl set-sink-volume @DEFAULT_SINK@ +" step "%"))
  "lower" (sh "bash" "-c" (str "pactl set-sink-volume @DEFAULT_SINK@ -" step "%"))
  "mute" (sh "bash" "-c" "pactl set-sink-mute @DEFAULT_SINK@ toggle")
  "get" (get-volume)
  (prn (str "Invalid arg " cmd)))