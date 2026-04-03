package com.mta.faultinjection.interceptor;

import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

/**
 * ExchangeFilterFunction for WebClient. Intended hook for injecting delays or
 * errors into reactive HTTP exchanges.
 */
public class FaultInjectionFilter implements ExchangeFilterFunction {

    /**
     * Filters the outbound reactive HTTP exchange.
     *
     * Library behavior: consult the configured decision strategy and, based on the
     * returned instruction, either delay, fail fast, or proceed. The default
     * implementation is a pass-through.
     *
     * @param request the outbound HTTP request
     * @param next the next exchange function in the chain
     * @return a Mono that emits the response
     */
    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        // TODO: Consult FaultDecisionStrategy and apply delay/error when instructed
        return next.exchange(request);
    }
}
