(ns silo.service.health.infrastructure.memory-test
  (:require [clojure.test :refer [deftest is testing]]
            [silo.service.health.api :as api]
            [silo.service.health.infrastructure.memory :as memory]))

(deftest service-starts-healthy-with-independent-empty-checks
  (let [first-service (memory/service)
        second-service (memory/service)
        check-status (api/unhealthy "Connection refused")]
    (is (satisfies? api/Service first-service))
    (is (= {:health :healthy :checks {}} (api/status first-service)))
    (api/add-check! first-service :database check-status)
    (is (= {:health :unhealthy :checks {:database check-status}}
           (api/status first-service)))
    (is (= {:health :healthy :checks {}} (api/status second-service)))))

(deftest overall-health-follows-the-worst-check-regardless-of-insertion-order
  (doseq [[checks expected-health]
          [[[:healthy :healthy] :healthy]
           [[:healthy :degraded] :degraded]
           [[:degraded :healthy] :degraded]
           [[:healthy :unhealthy] :unhealthy]
           [[:unhealthy :healthy] :unhealthy]
           [[:degraded :unhealthy] :unhealthy]
           [[:unhealthy :degraded] :unhealthy]]]
    (testing (pr-str checks)
      (let [service (memory/service)
            statuses (mapv #(hash-map :health %) checks)
            expected {:health expected-health
                      :checks (zipmap [:database :cache] statuses)}]
        (api/add-check! service :database (first statuses))
        (is (= expected (api/add-check! service :cache (second statuses))))
        (is (= expected (api/status service)))))))

(deftest updating-a-check-preserves-other-checks-and-recomputes-health
  (let [service (memory/service)
        cache-status (api/degraded "Cache is slow")]
    (api/add-check! service :cache cache-status)
    (doseq [database-status [(api/unhealthy "Database unavailable")
                             (api/degraded "Database is slow")
                             (api/healthy)]]
      (let [expected {:health (if (= :unhealthy (:health database-status))
                                :unhealthy
                                :degraded)
                      :checks {:cache cache-status :database database-status}}]
        (is (= expected (api/add-check! service :database database-status)))
        (is (= expected (api/status service)))))
    (is (= {:health :healthy
            :checks {:cache (api/healthy) :database (api/healthy)}}
           (api/add-check! service :cache (api/healthy))))))

(deftest invalid-check-keys-are-rejected-without-changing-state
  (let [service (memory/service)
        check-status (api/healthy)]
    (api/add-check! service :database check-status)
    (doseq [k [nil "database" 'database 1 false [] {}]]
      (testing (pr-str k)
        (let [before (api/status service)
              error (try
                      (api/add-check! service k check-status)
                      nil
                      (catch clojure.lang.ExceptionInfo error
                        error))]
          (is (instance? clojure.lang.ExceptionInfo error))
          (is (= "Invalid check key" (some-> error .getMessage)))
          (is (= {:key k} (ex-data error)))
          (is (= before (api/status service))))))))

(deftest invalid-check-statuses-are-rejected-without-replacing-existing-checks
  (let [service (memory/service)]
    (api/add-check! service :database (api/healthy))
    (doseq [check-status [nil :healthy [] {} {:reason "Missing health"}
                          {:health nil} {:health :unknown} {:health "healthy"}
                          {:health :healthy :reason nil}
                          {:health :degraded :reason ""}
                          {:health :unhealthy :reason " \t\n"}
                          {:health :unhealthy :reason 42}]]
      (testing (pr-str check-status)
        (let [before (api/status service)
              error (try
                      (api/add-check! service :database check-status)
                      nil
                      (catch clojure.lang.ExceptionInfo error
                        error))]
          (is (instance? clojure.lang.ExceptionInfo error))
          (is (= "Invalid check status" (some-> error .getMessage)))
          (is (= {:key :database :status check-status} (ex-data error)))
          (is (= before (api/status service))))))))

(deftest shutdown-is-repeatable-and-does-not-change-the-stored-status
  (let [service (memory/service)
        check-status (api/degraded "Database is slow")]
    (api/add-check! service :database check-status)
    (is (nil? (api/shutdown! service)))
    (is (nil? (api/shutdown! service)))
    (is (= {:health :degraded :checks {:database check-status}}
           (api/status service)))))