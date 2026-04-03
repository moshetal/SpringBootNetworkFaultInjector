Spring Boot Fault Injector Starter

Description
- A lightweight Spring Boot starter that wires interceptors/filters into popular HTTP clients to simulate network faults (delays/errors) for testing scenarios.

Features
- Auto-configuration for:
  - RestClient (Spring Framework 6.1+/Boot 3.2+)
  - RestTemplate
  - WebClient (provides an ExchangeFilterFunction bean)
- FaultInjectionManager entry point for decision-making
- Configuration properties container (FaultInjectionProperties)
- Optional Spring Boot Actuator endpoint (id=faultinjector)

Compatibility
- Java 17+
- Spring Boot 3.2.x

Installation
Maven:
<dependency>
  <groupId>com.mta</groupId>
  <artifactId>spring-boot-fault-injector-starter</artifactId>
  <version>0.0.1-SNAPSHOT</version>
</dependency>

Gradle (Groovy DSL):
implementation 'com.mta:spring-boot-fault-injector-starter:0.0.1-SNAPSHOT'

Usage
- RestClient (interceptor applied via RestClientCustomizer):
  @Bean
  RestClient restClient(RestClient.Builder builder) {
      return builder
          .baseUrl("https://api.example.com")
          .build();
  }

- RestTemplate (interceptor applied via RestTemplateCustomizer):
  @Bean
  RestTemplate restTemplate(RestTemplateBuilder builder) {
      return builder.build();
  }

- WebClient (a FaultInjectionFilter bean is available):
  @Bean
  WebClient webClient(WebClient.Builder builder) {
      return builder.build();
  }

Configuration
- Properties prefix: fault.injection
- Example application.yml:
  fault:
    injection:
      enabled: true
      # defaultLatency: 200ms
      # services:
      #   users: { errorRate: 0.1 }
- Example application.properties:
  fault.injection.enabled=true
  # fault.injection.default-latency=200ms
  # fault.injection.services.users.error-rate=0.1

Actuator endpoint (optional)
- If spring-boot-starter-actuator is on the classpath:
  - /actuator/faultinjector (id: faultinjector)

Build
- Using Maven Wrapper:
  ./mvnw clean verify

- Run tests only:
  ./mvnw test

License
- Add a LICENSE file if you need an explicit license.
