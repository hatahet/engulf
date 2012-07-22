(ns engulf.worker-client
  (:require
   [engulf.utils :as utils]
   [engulf.comm.netchan :as nc]
   [engulf.formula :as formula]
   [clojure.tools.logging :as log]
   [lamina.core :as lc])
  (:use lamina.core
        [clojure.walk :only [keywordize-keys]])
  (:import java.util.UUID
           java.net.ConnectException))

(def uuid (str (UUID/randomUUID)))

(def current (agent nil))

(defn job-results-channel
  "Returns a channel suitable for hooking up to start-edge. It will route all results through the network connection with the proper metadata and message formatting"
  [{uuid :uuid :as job} conn]
  (when (not uuid)
    (throw (Exception. (str "Missing UUID for job!" job))))
  (let [ch (channel* :permanent? true :grounded? true)]
    (siphon
     (map* (fn jres-map [res] {:job-uuid uuid :result res}) ch )
     conn)
    ch))

(defn start-job
  "Bridge the streaming results from the job-formula to the connection
   They get routed through a permanent channel to prevent close events from
   propagating"
  [job conn]
  (utils/safe-send-off-with-result current res state
    (log/info (str "Starting job on worker: " job))
    (when-let [{old-fla :formula} state] (formula/stop old-fla))
    (let [res-ch (job-results-channel job conn)
          fla (formula/init-job-formula job)]
      (siphon (formula/start-edge fla) res-ch)
      (lc/enqueue res res-ch)
      {:job job :formula fla :results-channel res-ch})))


(defn stop-job
  "Stop the current job, setting current to nil"
  []
  (utils/safe-send-off-with-result current res state
    (when-let [{old-fla :formula res-ch :results-channel} state]
      (lc/enqueue res (formula/stop old-fla))
      (lc/close res-ch))
    nil))

(defn handle-message
  [conn {:strs [name body] :as msg}]
  (try
    (let [name (keyword name)
          body (keywordize-keys body)]
      (condp = name
        :job-start (start-job body conn)
        :job-stop (stop-job)
        (log/warn (str "Worker received unexpected msg" msg))))
    (catch Throwable t
      (log/warn t (str "Worker could not handle message!" msg)))))

(defn client-connect
  [host port]
  (try
    (let [conn @(nc/client-connect host port)]
      (on-closed conn (fn [] (log/warn "Connection to master closed!")))
      (on-error conn (fn [e] (log/warn e "Server Channel Error!") ))
      (receive-all conn (partial handle-message conn))
      ;; Send identity immediately
      (enqueue conn {:name "uuid" :body uuid})
      conn)
    (catch java.net.ConnectException e
      (log/warn e "Could not connect to control server!"))))