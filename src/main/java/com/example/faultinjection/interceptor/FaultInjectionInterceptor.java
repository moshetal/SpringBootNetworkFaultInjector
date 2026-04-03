package com.example.faultinjection.interceptor;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Network Interceptor (Sensor & Actuator) for RestTemplate
 * Role: Intercepts synchronous HTTP calls, would query the Rule Engine,
 * and could force delays or errors if enabled.
 *
 * Note: Structure only. No fault injection logic implemented.
 */
public class FaultInjectionInterceptor implements ClientHttpRequestInterceptor {
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        // Placeholder: consult rule engine and possibly alter behavior
        return execution.execute(request, body);
    }
}
