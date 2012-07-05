(ns engulf.formulas.http-benchmark
  (:require [lamina.core :as lc]
            [clojure.tools.logging :as log]
            [clojure.set :as cset])
  (:use [engulf.formula :only [Formula register]]
        [engulf.utils :only [set-timeout]]
        [aleph.http :only [http-client http-request]])
  (:import fastPercentiles.PercentileRecorder))

(defn increment-keys
  "Given a map and a list of keys, this will return an identical map with those keys
   with those keys incremented.
    
   Keys with a null value will be set to 1."
  [src-map & xs]
  (into src-map (map #(vector %1 (inc (get src-map %1 0))) xs)))

(defn empty-aggregation
  [params]
  {:runtime nil
   :runs-sec nil
   :runs-total 0
   :runs-succeeded 0
   :runs-failed 0
   :response-code-counts {}
   :by-start-time {}
   :runtime-percentiles-recorder (PercentileRecorder. (or (:timeout params) 10000))})

(defn run-request
  [params callback]
  (let [res (lc/result-channel)] ; (http-request {:url (:url params)})
    (set-timeout 1 #(lc/success res {}))
    (lc/on-realized res #(callback %1) #(callback %1))))

(defn aggregate
  [params results]
  (-> (empty-aggregation params)
      (assoc :runs-total (count results))))

(defn validate-params [params]
  (let [diff (cset/difference #{:url :method :concurrency} params)]
    (when (not (empty? diff))
      (throw (Exception. "Invalid parameters! Missing keys: " diff))
      )))

(defprotocol IHttpBenchmark
  (run-repeatedly [this]))

(defrecord HttpBenchmark [state params res-ch]
  IHttpBenchmark
  (run-repeatedly [this]
    (run-request
     params
     (fn req-resp [res]
       (when (= @state :started) ; Discard results and don't recur when stopped
         (lc/enqueue res-ch res)
         (run-repeatedly this)))))  
  Formula
  (start-relay [this]
    
    )
  (start-edge [this]
    (validate-params params)
    (if (not (compare-and-set! state :initialized :started))
      (throw (Exception. (str "Expected state :initialized, not: ") @state))
      (do
        (dotimes [t (Integer/valueOf (:concurrency params))] (run-repeatedly this))
        (lc/map* (partial aggregate params) (lc/partition-every 250 res-ch)))))
  (stop [this]
    (reset! state :stopped)))

(defn init-benchmark
  [params]
  (HttpBenchmark. (atom :initialized)
                  params
                  (lc/channel)))

(register :http-benchmark init-benchmark)
