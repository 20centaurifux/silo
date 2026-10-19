(ns silo.service.health.specs-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [silo.service.health.specs :as specs]))

(deftest health-spec-accepts-only-supported-status-values
  (testing "supported health values"
    (doseq [health [:healthy :degraded :unhealthy]]
      (is (s/valid? ::specs/health health) (pr-str health))))

  (testing "unsupported values and types"
    (doseq [health [:unknown ::healthy "healthy" nil true 1 {}]]
      (is (not (s/valid? ::specs/health health)) (pr-str health)))))

(deftest reason-spec-requires-a-non-blank-string
  (testing "non-blank reasons"
    (doseq [reason ["Database unavailable" " retrying " "\nTimeout\n"]]
      (is (s/valid? ::specs/reason reason) (pr-str reason))))

  (testing "blank reasons and non-string values"
    (doseq [reason ["" " " "\t\r\n" nil :timeout 1 false [] {}]]
      (is (not (s/valid? ::specs/reason reason)) (pr-str reason)))))

(deftest status-spec-requires-health-and-validates-optional-reason
  (testing "every supported health value with and without a reason"
    (doseq [health [:healthy :degraded :unhealthy]
            status [{:health health}
                    {:health health :reason "Check completed"}]]
      (is (s/valid? ::specs/status status) (pr-str status))))

  (testing "missing health, invalid fields and non-map values"
    (doseq [status [{}
                    {:reason "Database unavailable"}
                    {::specs/health :healthy}
                    {:health :unknown}
                    {:health nil}
                    {:health "healthy"}
                    {:health :degraded :reason ""}
                    {:health :degraded :reason " \t\n"}
                    {:health :unhealthy :reason nil}
                    {:health :unhealthy :reason :timeout}
                    nil [] :healthy]]
      (is (not (s/valid? ::specs/status status)) (pr-str status)))))

(deftest checks-spec-requires-keyword-keys-and-valid-statuses
  (testing "empty checks and checks with plain or namespaced keyword keys"
    (doseq [checks [{}
                    {:database {:health :healthy}}
                    {:database {:health :unhealthy :reason "Connection refused"}
                     :cache {:health :degraded :reason "Retrying"}
                     :service/queue {:health :healthy}}]]
      (is (s/valid? ::specs/checks checks) (pr-str checks))))

  (testing "invalid keys, invalid nested statuses and non-map values"
    (doseq [checks [{"database" {:health :healthy}}
                    {1 {:health :healthy}}
                    {nil {:health :healthy}}
                    {:database {}}
                    {:database {:health :unknown}}
                    {:database {:health :degraded :reason " "}}
                    {:database nil}
                    {:database :healthy}
                    {:database {:health :healthy}
                     :cache {:health :unhealthy :reason nil}}
                    nil [] [[:database {:health :healthy}]]]]
      (is (not (s/valid? ::specs/checks checks)) (pr-str checks)))))

(deftest health-status-spec-requires-health-and-checks
  (testing "overall health with empty or populated checks"
    (doseq [health [:healthy :degraded :unhealthy]
            checks [{} {:database {:health health :reason "Check completed"}}]]
      (is (s/valid? ::specs/health-status {:health health :checks checks})
          (pr-str {:health health :checks checks}))))

  (testing "missing fields, invalid overall health and invalid nested checks"
    (doseq [status [{}
                    {:health :healthy}
                    {:checks {}}
                    {::specs/health :healthy ::specs/checks {}}
                    {:health :unknown :checks {}}
                    {:health nil :checks {}}
                    {:health "healthy" :checks {}}
                    {:health :healthy :checks nil}
                    {:health :healthy :checks []}
                    {:health :healthy :checks {"database" {:health :healthy}}}
                    {:health :healthy :checks {:database {}}}
                    {:health :healthy :checks {:database {:health :unknown}}}
                    {:health :degraded
                     :checks {:database {:health :degraded :reason ""}}}
                    nil [] :healthy]]
      (is (not (s/valid? ::specs/health-status status)) (pr-str status)))))