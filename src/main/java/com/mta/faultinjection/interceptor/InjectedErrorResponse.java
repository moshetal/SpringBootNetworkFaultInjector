package com.mta.faultinjection.interceptor;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Synthetic {@link ClientHttpResponse} returned by {@link FaultInjectionInterceptor}
 * when a rule short-circuits an outbound request with an injected error.
 */
final class InjectedErrorResponse implements ClientHttpResponse {

    private final HttpStatusCode status;
    private final String statusText;
    private final byte[] body;
    private final HttpHeaders headers;

    InjectedErrorResponse(int status, String message) {
        HttpStatus resolved = HttpStatus.resolve(status);
        this.status = resolved != null ? resolved : HttpStatusCode.valueOf(status);
        this.statusText = resolved != null ? resolved.getReasonPhrase() : "Injected Fault";
        this.body = (message == null ? "" : message).getBytes(StandardCharsets.UTF_8);
        this.headers = new HttpHeaders();
        this.headers.setContentType(MediaType.TEXT_PLAIN);
        this.headers.setContentLength(this.body.length);
    }

    @Override
    public HttpStatusCode getStatusCode() {
        return status;
    }

    @Override
    public String getStatusText() {
        return statusText;
    }

    @Override
    public void close() {
        // no resources to release
    }

    @Override
    public InputStream getBody() {
        return new ByteArrayInputStream(body);
    }

    @Override
    public HttpHeaders getHeaders() {
        return headers;
    }
}
