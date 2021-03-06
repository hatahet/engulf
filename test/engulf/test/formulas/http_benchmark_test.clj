(ns engulf.test.formulas.http-benchmark-test
  (:require
   [lamina.core :as lc]
   [engulf.formulas.http-benchmark :as htb]
   [engulf.test.helpers :as helpers]
   [engulf.formula :as fla])
  (:use midje.sweet))

(defn init-benchmark
  []
  (htb/init-benchmark helpers/test-http-job-params
                      {:params helpers/test-http-job-params}))

(facts
 "about initializing a benchmark"
 (let [b (init-benchmark)]
   (fact
    "it should be an engulf benchmark"
    (class b) => engulf.formulas.http_benchmark.HttpBenchmark)
   (fact
    "it should have a state of :initialized"
    @(:state b) => :initialized)))

(facts
 "about starting an edge"
 (let [b (init-benchmark)
       res-ch (fla/start-edge b)]
   (fact
    "it should return nil if it's already started"
    (fla/start-edge b) => nil)
   (fact
    "it should change state to started"
    @(:state b) => :started)
   (fact
    "it should return aggregate data in short order"
    (get @(lc/read-channel* res-ch :timeout 300) "type") => "aggregate-edge")
   (fact
    "it should stop cleanly"
    (fla/stop b) => truthy)
   (fact
    "blah"
    (:res-ch b) => lc/closed?)))

(facts
 "about starting a relay"
   (let [b (init-benchmark)
         res-ch (fla/start-relay b (lc/channel))]
   (fact
    "it should return nil if it's already started"
    (fla/start-relay b (lc/channel)) => nil)
   (fact
    "it should change state to started"
    @(:state b) => :started)
   (fact
    "it should return aggregate data in short order"
    (get @(lc/read-channel* res-ch :timeout 300) "type") => "aggregate-relay")
   (fact
    "it should stop cleanly"
    (fla/stop b) => truthy)
   (fact
    "blah"
    (:res-ch b) => lc/closed?)))

(defn eagg
  []
  (htb/edge-aggregate {:timeout 500}
                          [(htb/success-result 0 10 200)
                           (htb/success-result 0 10 200)
                           (htb/success-result 0 20 404)
                           (htb/error-result 550 590 (Exception. "wtf"))]))

(facts
 "about relay aggregation"
 (let [params {:timeout 500 :limit 500}
       agg (htb/relay-aggregate
            {:params params :started-at 100}
            (htb/empty-relay-aggregation params) (repeatedly 2 eagg))]
   (fact
    "it should sum run totals"
    (agg "runs-total") => 8)
   (fact
    "it should sum success totals"
    (agg "runs-succeeded") => 6)
   (fact
    "it should sum failure totals"
    (agg "runs-failed") => 2)
   (fact
    "it should sum runtimes"
    (agg "runtime") => 160)
   (fact
    "it should merge time slices"
    (agg "time-slices") => {0 {200 4, 404 2 "total" 6}, 500 {"total" 2 "thrown" 2}}
    )
   (fact
    "it should sum aggregated statuses"
    (agg "status-codes") => {"thrown" 2, 404 2, 200 4}
    )))

(facts
 "about edge aggregation"
 (let [agg (eagg)]
   (fact
    "it should set the runs-total to the length of the dataset"
    (agg "runs-total")  => 4)
   (fact
    "it should count successes correctly"
    (agg "runs-succeeded") => 3)
   (fact
    "it should count failures correctly"
    (agg "runs-failed") => 1)
   (fact
    "it should add up times correctly"
    (agg "runtime") => 80)
   (fact
    "it should properly aggregate the status codes"
    (agg "status-codes") => {200 2
                            404 1
                            "thrown" 1})
   (fact
    "it should record as many samples as given"
    (count (agg "all-runtimes")) => 4)
   (fact
    "it should aggregate response codes by time-slice"
    (agg "time-slices") => {0 {404 1, 200 2 "total" 3}
                            500 {"thrown" 1 "total" 1}})))

