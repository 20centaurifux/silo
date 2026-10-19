(ns silo.transport.http.middleware-test
  (:require [clojure.test :refer [deftest is testing]]
            [silo.transport.http.middleware :as middleware])
  (:import [java.security.cert CertificateExpiredException
            CertificateNotYetValidException X509Certificate]
           [javax.security.auth.x500 X500Principal]))

(defn- certificate
  ([subject]
   (certificate subject nil))
  ([subject validity-error]
   (proxy [X509Certificate] []
     (getSubjectX500Principal []
       (X500Principal. subject))
     (checkValidity []
       (when validity-error
         (throw validity-error))))))

(deftest common-name-is-parsed-and-added-to-the-request
  (doseq [[subject expected-cn] [["CN=service.local,OU=Services,O=Silo" "service.local"]
                                 ["OU=Services,CN=service.local,O=Silo" "service.local"]
                                 ["CN=service\\, east,O=Silo" "service, east"]
                                 ["CN=service\\+east,O=Silo" "service+east"]]]
    (testing subject
      (let [request {:ssl-client-cert (certificate subject)
                     :common-name "untrusted-input"
                     :request-method :get
                     :uri "/health"}
            response {:status 202 :body "accepted"}
            handler (middleware/wrap-common-name
                     (fn [actual-request]
                       (is (= (assoc request :common-name expected-cn) actual-request))
                       response))]
        (is (identical? response (handler request)))))))

(deftest common-name-rejects-missing-certificates-and-subjects-without-cn
  (doseq [[description request] [["missing certificate" {}]
                                 ["nil certificate" {:ssl-client-cert nil}]
                                 ["subject without CN"
                                  {:ssl-client-cert (certificate "OU=Services,O=Silo")}]]]
    (testing description
      (let [handler (middleware/wrap-common-name
                     (fn [_]
                       (is false "Handler must not be called")))]
        (is (= {:status 403
                :headers {"content-type" "text/plain; charset=utf-8"}
                :body "Client certificate required"}
               (handler (assoc request :common-name "untrusted-input"))))))))

(deftest valid-certificate-preserves-the-request-and-response
  (let [request {:ssl-client-cert (certificate "CN=service.local")
                 :request-method :get
                 :uri "/health"}
        response {:status 202 :body "accepted"}
        handler (middleware/wrap-certificate-validity-period
                 (fn [actual-request]
                   (is (identical? request actual-request))
                   response))]
    (is (identical? response (handler request)))))

(deftest valid-certificate-rejects-missing-expired-and-not-yet-valid-certificates
  (doseq [[description request] [["missing certificate" {}]
                                 ["nil certificate" {:ssl-client-cert nil}]
                                 ["expired certificate"
                                  {:ssl-client-cert (certificate "CN=service.local"
                                                                 (CertificateExpiredException.))}]
                                 ["not yet valid certificate"
                                  {:ssl-client-cert (certificate "CN=service.local"
                                                                 (CertificateNotYetValidException.))}]]]
    (testing description
      (let [handler (middleware/wrap-certificate-validity-period
                     (fn [_]
                       (is false "Handler must not be called")))]
        (is (= {:status 403
                :headers {"content-type" "text/plain; charset=utf-8"}
                :body "Invalid client certificate"}
               (handler request)))))))

(deftest valid-certificate-propagates-unexpected-validation-errors
  (let [handler (middleware/wrap-certificate-validity-period
                 (fn [_]
                   (is false "Handler must not be called")))
        request {:ssl-client-cert (certificate "CN=service.local"
                                               (ex-info "Certificate validation failed" {}))}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Certificate validation failed"
                          (handler request)))))

(deftest combined-middleware-passes-valid-certificates-with-their-common-name
  (let [request {:ssl-client-cert (certificate "CN=service.local")
                 :request-method :get
                 :uri "/health"}
        response {:status 200 :body "healthy"}
        handler (-> (fn [actual-request]
                      (is (= (assoc request :common-name "service.local") actual-request))
                      response)
                    middleware/wrap-certificate-validity-period
                    middleware/wrap-common-name)]
    (is (identical? response (handler request)))))

(deftest combined-middleware-requires-both-cn-and-validity
  (doseq [[description cert expected-body]
          [["missing certificate" nil "Client certificate required"]
           ["valid certificate without CN" (certificate "O=Silo") "Client certificate required"]
           ["expired certificate with CN"
            (certificate "CN=service.local" (CertificateExpiredException.))
            "Invalid client certificate"]
           ["not yet valid certificate with CN"
            (certificate "CN=service.local" (CertificateNotYetValidException.))
            "Invalid client certificate"]]]
    (testing description
      (let [handler (-> (fn [_]
                          (is false "Handler must not be called"))
                        middleware/wrap-certificate-validity-period
                        middleware/wrap-common-name)]
        (is (= {:status 403
                :headers {"content-type" "text/plain; charset=utf-8"}
                :body expected-body}
               (handler {:ssl-client-cert cert})))))))
