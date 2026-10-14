.PHONY: clean ca service client

# Certificate signing shares the CA serial file, so run recipes sequentially.
.NOTPARALLEL:

all: ca service client

clean:
	rm -fr ./ca ./service ./client

ca:
	mkdir -p ./ca

	openssl ecparam \
	-genkey \
	-name prime256v1 \
	-noout \
	-out ./ca/ca.key

	openssl req \
	-new \
	-x509 \
	-key ./ca/ca.key \
	-sha256 \
	-days 3650 \
	-subj "/CN=test-ca" \
	-addext "keyUsage=critical,keyCertSign,cRLSign" \
	-out ./ca/ca.crt

	keytool -importcert \
	-noprompt \
	-alias test-ca \
	-file ./ca/ca.crt \
	-keystore ./ca/truststore.p12 \
	-storetype PKCS12 \
	-storepass "trustno1" \
	-keypass "trustno1"

service: ca
	mkdir -p ./service

	openssl ecparam \
	-genkey \
	-name prime256v1 \
	-noout \
	-out ./service/service.key

	openssl req \
	-new \
	-key ./service/service.key \
	-subj "/CN=service" \
	-out ./service/service.csr

	openssl x509 \
	-req \
	-in service/service.csr \
	-CA ./ca/ca.crt \
	-CAkey ./ca/ca.key \
	-CAcreateserial \
	-days 90 \
	-sha256 \
	-extfile ./service.ext \
	-out ./service/service.crt

	P12_PASSWORD="trustno1" openssl pkcs12 \
	-export \
	-name service \
	-inkey ./service/service.key \
	-in ./service/service.crt \
	-certfile ./ca/ca.crt \
	-out ./service/service.p12 \
	-passout env:P12_PASSWORD

client: ca
	mkdir -p ./client

	openssl ecparam \
	-genkey \
	-name prime256v1 \
	-noout \
	-out ./client/client.key

	openssl req \
	-new \
	-key ./client/client.key \
	-subj "/CN=client" \
	-out ./client/client.csr

	openssl x509 \
	-req \
	-in client/client.csr \
	-CA ./ca/ca.crt \
	-CAkey ./ca/ca.key \
	-CAcreateserial \
	-days 90 \
	-sha256 \
	-extfile ./client.ext \
	-out ./client/client.crt

	P12_PASSWORD="trustno1" openssl pkcs12 \
	-export \
	-name client \
	-inkey ./client/client.key \
	-in ./client/client.crt \
	-certfile ./ca/ca.crt \
	-out ./client/client.p12 \
	-passout env:P12_PASSWORD