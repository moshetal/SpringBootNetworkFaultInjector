# Fault Injector Platform

Cluster control plane (STOMP server + console UI + PostgreSQL). This tree is **not** part of the published library Maven reactor.

## Full stack demo (server + demo pods)

From the repository root:

```bash
make demo-cloud
```

See [examples/README.md](../examples/README.md) for ports and details.

## Build server locally

Install the library first:

```bash
mvn install -DskipTests
```

Then build and run the server:

```bash
mvn -f platform/pom.xml clean package
java -jar platform/fault-injector-server/target/fault-injector-server-0.0.1-SNAPSHOT.jar
```

Requires PostgreSQL at `jdbc:postgresql://localhost:5432/faultinjector` (user/password: `faultinjector`).

Console UI: http://localhost:8080/console/

## Enable agent in your app

Add the optional starter (not included in the default fault injector starter):

```xml
<dependency>
  <groupId>com.mta.faultinjector</groupId>
  <artifactId>spring-boot-starter-fault-injector-agent</artifactId>
  <version>0.0.1-SNAPSHOT</version>
</dependency>
```

```yaml
fault:
  injection:
    agent:
      enabled: true
      server-url: ws://fault-injector-server:8080/ws
      service-name: ${spring.application.name}
      instance-id: ${HOSTNAME:}
```

Local UI at `/fault-injector` continues to work alongside the agent.
