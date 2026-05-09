package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Demo app that exercises the fault-injection starter against a public echo
 * endpoint. The starter's auto-config attaches its interceptor/filter to each
 * client below — so whenever {@code fault.injection.rules} match the request,
 * we see injected latency or synthetic error responses.
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @Bean
    RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.build();
    }

    @Bean
    RestClient restClient(RestClient.Builder builder) {
        return builder.build();
    }

    @Bean
    WebClient webClient(WebClient.Builder builder) {
        return builder.build();
    }
}
