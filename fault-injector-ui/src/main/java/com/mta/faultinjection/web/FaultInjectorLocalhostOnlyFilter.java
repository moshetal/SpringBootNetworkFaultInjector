package com.mta.faultinjection.web;

import com.mta.faultinjection.config.FaultInjectionProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import org.springframework.http.HttpStatus;

/**
 * Rejects requests to the UI prefix when the client is not connecting from a
 * loopback address. Complements real authentication; not sufficient alone.
 */
public final class FaultInjectorLocalhostOnlyFilter extends HttpFilter {

    private final String prefix;

    public FaultInjectorLocalhostOnlyFilter(FaultInjectionProperties properties) {
        String p = properties.getUi().getPath();
        if (p == null || p.isBlank()) {
            p = "/fault-injector";
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        this.prefix = p;
    }

    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        String uri = req.getRequestURI();
        if (uri != null && (uri.equals(prefix) || uri.startsWith(prefix + "/"))) {
            if (!isLocal(req)) {
                res.sendError(HttpStatus.FORBIDDEN.value(), "fault.injection.ui.require-localhost is enabled");
                return;
            }
        }
        chain.doFilter(req, res);
    }

    private static boolean isLocal(HttpServletRequest req) {
        String addr = req.getRemoteAddr();
        if (addr == null) {
            return false;
        }
        try {
            return InetAddress.getByName(addr).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }
}
