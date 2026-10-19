(ns silo.service.health.api-test
  (:require [clojure.test :refer [deftest is testing]]
            [silo.service.health.api :as api]))

(deftest healthy-returns-status-without-reason
  (is (= {:health :healthy} (api/healthy))))

(deftest degraded-returns-status-with-optional-reason
  (testing "without a reason"
    (is (= {:health :degraded} (api/degraded))))

  (testing "with a reason preserved verbatim"
    (doseq [reason ["Database is slow" " retrying " "\nTimeout\n"]]
      (is (= {:health :degraded :reason reason}
             (api/degraded reason))
          (pr-str reason)))))

(deftest unhealthy-returns-status-with-optional-reason
  (testing "without a reason"
    (is (= {:health :unhealthy} (api/unhealthy))))

  (testing "with a reason preserved verbatim"
    (doseq [reason ["Database unavailable" " connection refused " "\nTimeout\n"]]
      (is (= {:health :unhealthy :reason reason}
             (api/unhealthy reason))
          (pr-str reason)))))

(deftest invalid-reasons-include-health-and-reason-in-exception-data
  (doseq [[health status-fn] [[:degraded api/degraded]
                              [:unhealthy api/unhealthy]]]
    (testing (name health)
      (doseq [reason ["" " " "\t\r\n" nil :timeout 1 false [] {}]]
        (testing (pr-str reason)
          (let [error (try
                        (status-fn reason)
                        nil
                        (catch clojure.lang.ExceptionInfo error
                          error))]
            (is (instance? clojure.lang.ExceptionInfo error))
            (is (= "Invalid reason" (some-> error .getMessage)))
            (is (= {:health health :reason reason} (ex-data error)))))))))