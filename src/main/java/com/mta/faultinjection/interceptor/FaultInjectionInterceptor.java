package com.mta.faultinjection.interceptor;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * ClientHttpRequestInterceptor used by both RestTemplate and RestClient.
 * Intended hook for injecting delays or errors around outbound HTTP requests.
 */
public class FaultInjectionInterceptor implements ClientHttpRequestInterceptor {

    /**
     * Intercepts the outbound HTTP request.
     * <p>
     * Invoked by both RestTemplate and RestClient when this interceptor is registered
     * (via RestTemplateCustomizer or RestClientCustomizer in this starter).
     *
     * Library behavior: consult the configured decision strategy and, based on the
     * returned instruction, either delay, fail fast, or proceed. The default
     * implementation is a pass-through.
     *
     * @param request the HTTP request
     * @param body request body in bytes
     * @param execution next element in the interceptor chain
     * @return the HTTP response from the execution chain
     * @throws IOException if an I/O error occurs
     */
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        // TODO: Consult FaultDecisionStrategy and apply delay/error when instructed
        return execution.execute(request, body);
    }
}
