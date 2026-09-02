.PHONY: demo-local demo-cloud demo-node install-lib verify

install-lib:
	mvn -q install -DskipTests

demo-local: install-lib
	mvn -f examples/fault-injector-demo/pom.xml spring-boot:run

demo-cloud:
	docker compose -f examples/docker/docker-compose.yml up --build

demo-node: export JAVA_HOME := /opt/homebrew/opt/openjdk@17
demo-node: export PATH := /opt/homebrew/opt/openjdk@17/bin:/opt/homebrew/bin:$(PATH)
demo-node: install-lib
	mvn -pl fault-injector-sidecar -am package -DskipTests
	cd sdk/node && npm install && npm run build
	cd examples/fault-injector-node-demo && npm install && npm start

verify:
	mvn clean verify
	mvn -f platform/pom.xml clean verify
