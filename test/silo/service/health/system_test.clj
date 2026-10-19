(ns silo.service.health.system-test
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [muuntaja.core :as m]
            [reitit.ring :as ring]
            [silo.service.health.api :as api]
            [silo.service.health.infrastructure.memory :as impl]
            [silo.service.health.system :as health]))

(deftest service-component-starts-with-independent-empty-checks
  (let [first-service (ig/init-key :health/service {})
        second-service (ig/init-key :health/service {})]
    (try
      (is (satisfies? api/Service first-service))
      (is (satisfies? api/Service second-service))
      (is (= {:health :healthy :checks {}} (api/status first-service)))
      (api/add-check! first-service :database (api/unhealthy "Connection refused"))
      (is (= {:health :unhealthy
              :checks {:database (api/unhealthy "Connection refused")}}
             (api/status first-service)))
      (is (= {:health :healthy :checks {}} (api/status second-service)))
      (finally
        (ig/halt-key! :health/service second-service)
        (ig/halt-key! :health/service first-service)))))

(deftest integrant-resolves-the-service-reference-and-shuts-down-the-started-service
  (let [events (atom [])
        check-status (api/degraded "Database is slow")
        service (reify api/Service
                  (status [_]
                    {:health :degraded
                     :checks {:database check-status}})
                  (add-check! [_ _ _])
                  (shutdown! [this]
                    (swap! events conj [:stop this])))]
    (with-redefs [impl/service (fn []
                                 (swap! events conj :start)
                                 service)]
      (let [system (ig/init health/components)]
        (try
          (is (identical? service (:health/service system)))
          (is (= [:start] @events))
          (let [handler (get-in system [:health/routes 0 1 :get])]
            (is (= {:status 200
                    :body {:health :degraded :checks {:database check-status}}}
                   (handler {:request-method :get :uri "/health"}))))
          (finally
            (ig/halt! system)))
        (is (= [:start [:stop service]] @events))))))

(deftest health-route-reads-current-status-on-every-request
  (let [system (ig/init health/components)
        service (:health/service system)
        handler (get-in system [:health/routes 0 1 :get])
        request {:request-method :get :uri "/health"}]
    (try
      (testing "initial status without checks"
        (is (= {:status 200 :body {:health :healthy :checks {}}}
               (handler request))))

      (testing "updated checks, failures and recovery all return HTTP 200"
        (doseq [check-status [(api/healthy)
                              (api/degraded "Database is slow")
                              (api/unhealthy "Connection refused")
                              (api/healthy)]]
          (api/add-check! service :database check-status)
          (is (= {:status 200
                  :body {:health (:health check-status)
                         :checks {:database check-status}}}
                 (handler request))
              (pr-str check-status))))
      (finally
        (ig/halt! system)))))

(deftest health-routes-can-be-mounted-and-serialize-json-responses
  (let [system (ig/init health/components)
        handler (ring/ring-handler
                 (ring/router [["/api" (:health/routes system)]])
                 (ring/create-default-handler))]
    (try
      (api/add-check! (:health/service system)
                      :database
                      (api/degraded "Datenbank überlastet"))
      (testing "GET on the mounted route serializes the current status as JSON"
        (let [response (handler {:request-method :get
                                 :uri "/api/health"
                                 :headers {"accept" "application/json"}})]
          (is (= 200 (:status response)))
          (is (re-find #"^application/json(?:;|$)"
                       (get-in response [:headers "Content-Type"] "")))
          (is (= {:health "degraded"
                  :checks {:database {:health "degraded"
                                      :reason "Datenbank überlastet"}}}
                 (m/decode-response-body response)))))

      (testing "the route is available only beneath the application prefix"
        (is (= 404 (:status (handler {:request-method :get :uri "/health"}))))
        (is (= 404 (:status (handler {:request-method :get :uri "/api/unknown"})))))

      (testing "unsupported HTTP methods are rejected"
        (is (= 405 (:status (handler {:request-method :post :uri "/api/health"})))))
      (finally
        (ig/halt! system)))))