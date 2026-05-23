package com.mta.faultinjection.telemetry;

import java.net.URI;
import org.springframework.http.HttpMethod;

/**
 * Identifies a single (host, method, urlPath) tuple for retry-depth observation.
 * <p>
 * The path is taken from {@link URI#getPath()} so that retries which rotate
 * query-string nonces still collapse to the same key.
 */
public record TargetKey(String host, String method, String urlPath) {

    public static TargetKey fromOutbound(HttpMethod method, URI uri) {
        String host = uri == null || uri.getHost() == null ? "" : uri.getHost();
        String path = uri == null || uri.getPath() == null ? "" : uri.getPath();
        String m = method == null ? "" : method.name();
        return new TargetKey(host, m, path);
    }

    public HostMethodKey hostMethodKey() {
        return new HostMethodKey(host, method);
    }
}
