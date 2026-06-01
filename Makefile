.PHONY: demo-local demo-cloud install-lib verify

install-lib:
	mvn -q install -DskipTests

demo-local: install-lib
	mvn -f examples/fault-injector-demo/pom.xml spring-boot:run

demo-cloud:
	docker compose -f examples/docker/docker-compose.yml up --build

verify:
	mvn clean verify
	mvn -f platform/pom.xml clean verify
