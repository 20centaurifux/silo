(ns silo.transport.http.middleware
  (:require [clojure.string :as str])
  (:import [java.security.cert
            CertificateExpiredException
            CertificateNotYetValidException
            X509Certificate]
           [javax.naming.ldap LdapName Rdn]))

(defn- common-name
  [^X509Certificate certificate]
  (when certificate
    (let [subject (LdapName. (.getName (.getSubjectX500Principal certificate)))]
      (some (fn [^Rdn rdn]
              (when (= "CN" (str/upper-case (.getType rdn)))
                (str (.getValue rdn))))
            (.getRdns subject)))))

(defn wrap-common-name
  [handler]
  (fn [{:keys [ssl-client-cert] :as request}]
    (if-let [cn (common-name ssl-client-cert)]
      (handler (assoc request :common-name cn))
      {:status 403
       :headers {"content-type" "text/plain; charset=utf-8"}
       :body "Client certificate required"})))

(defn- certificate-within-validity-period?
  [^X509Certificate certificate]
  (when certificate
    (try
      (.checkValidity certificate)
      true
      (catch CertificateExpiredException _
        false)
      (catch CertificateNotYetValidException _
        false))))

(defn wrap-certificate-validity-period
  "Requires a client certificate whose validity period includes the current time."
  [handler]
  (fn [{:keys [ssl-client-cert] :as request}]
    (if (certificate-within-validity-period? ssl-client-cert)
      (handler request)
      {:status 403
       :headers {"content-type" "text/plain; charset=utf-8"}
       :body "Invalid client certificate"})))
