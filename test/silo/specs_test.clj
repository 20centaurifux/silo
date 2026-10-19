(ns silo.specs-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer :all]
            [silo.specs :as specs]))

(def ^:private valid-config
  {:http {:server {:binding {:host "127.0.0.1"
                             :port 8443}
                   :keystore {:path "certs/service.p12"
                              :password "secret"}
                   :truststore {:path "certs/truststore.p12"
                                :password "secret"}}}})

(deftest common-specs-accept-non-blank-strings
  (testing "non-blank strings"
    (is (s/valid? ::specs/non-blank-string "value"))
    (is (not (s/valid? ::specs/non-blank-string "")))
    (is (not (s/valid? ::specs/non-blank-string " \t\n")))
    (is (not (s/valid? ::specs/non-blank-string nil))))
  
  (testing "paths and passwords"
    (is (s/valid? ::specs/path "certs/service.p12"))
    (is (not (s/valid? ::specs/path " ")))
    (is (s/valid? ::specs/password "secret"))
    (is (not (s/valid? ::specs/password "")))))

(deftest host-spec-accepts-ip-addresses
  (is (s/valid? ::specs/host "127.0.0.1"))
  (is (s/valid? ::specs/host "2001:db8::1"))
  (is (not (s/valid? ::specs/host "")))
  (is (not (s/valid? ::specs/host "256.256.256.256"))))

(deftest port-spec-enforces-valid-range
  (is (s/valid? ::specs/port 1))
  (is (s/valid? ::specs/port 65535))
  (is (not (s/valid? ::specs/port 0)))
  (is (not (s/valid? ::specs/port 65536)))
  (is (not (s/valid? ::specs/port "8443")))
  (is (not (s/valid? ::specs/port 8443.5))))

(deftest binding-spec-requires-host-and-port
  (is (s/valid? ::specs/binding {:host "127.0.0.1" :port 8443}))
  (is (not (s/valid? ::specs/binding {:host "127.0.0.1"})))
  (is (not (s/valid? ::specs/binding {:port 8443})))
  (is (not (s/valid? ::specs/binding {:host "" :port 8443}))))

(deftest store-specs-require-non-blank-path-and-password
  (is (s/valid? ::specs/keystore {:path "service.p12" :password "secret"}))
  (is (not (s/valid? ::specs/keystore {:path "service.p12"})))
  (is (not (s/valid? ::specs/keystore {:path " " :password "secret"})))
  (is (s/valid? ::specs/truststore {:path "truststore.p12" :password "secret"}))
  (is (not (s/valid? ::specs/truststore {:path "truststore.p12" :password ""}))))

(deftest http-and-config-specs-validate-nested-settings
  (is (s/valid? :mtls.specs.http/server (:server (:http valid-config))))
  (is (s/valid? :mtls.specs/http (:http valid-config)))
  (is (s/valid? ::specs/config valid-config))
  (is (not (s/valid? ::specs/config {})))
  (is (not (s/valid? ::specs/config
                     (assoc-in valid-config [:http :server :binding :port] 65536)))))