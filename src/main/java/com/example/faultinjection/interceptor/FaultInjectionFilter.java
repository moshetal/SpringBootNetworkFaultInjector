package com.example.faultinjection.interceptor;

import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

/**
 * Network Interceptor (Sensor & Actuator) for WebClient
 * Role: Intercepts asynchronous Reactive calls, would query the Rule Engine,
 * and could insert delays or errors into the reactive chain.
 *
 * Note: Structure only. No fault injection logic implemented.
 */
public class FaultInjectionFilter implements ExchangeFilterFunction {
    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        // Placeholder: consult rule engine and possibly alter behavior
        return next.exchange(request);
    }
}
