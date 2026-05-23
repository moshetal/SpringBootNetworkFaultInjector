package com.mta.faultinjector.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FaultInjectorServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(FaultInjectorServerApplication.class, args);
    }
}
