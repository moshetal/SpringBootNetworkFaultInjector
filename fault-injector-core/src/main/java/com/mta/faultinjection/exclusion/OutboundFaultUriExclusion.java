package com.mta.faultinjection.exclusion;

import com.mta.faultinjection.config.FaultInjectionProperties;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Skips fault injection for outbound URIs that match management or UI URLs so
 * applications cannot accidentally fault their own control plane calls.
 */
public final class OutboundFaultUriExclusion {

    private final FaultInjectionProperties properties;
    private volatile String cacheDigest = "";
    private volatile List<Pattern> cachedPatterns = List.of();

    public OutboundFaultUriExclusion(FaultInjectionProperties properties) {
        this.properties = properties;
    }

    public boolean shouldExclude(URI uri) {
        if (!properties.isOutboundExcludeEnabled() || uri == null) {
            return false;
        }
        String url = uri.toString();
        for (Pattern p : compiledPatterns()) {
            if (p.matcher(url).find()) {
                return true;
            }
        }
        return false;
    }

    private List<Pattern> compiledPatterns() {
        List<String> raw = new ArrayList<>();
        if (properties.isOutboundExcludeIncludeBuiltins()) {
            raw.addAll(builtinPatterns());
        }
        if (properties.getOutboundExcludeUrlPatterns() != null) {
            for (String s : properties.getOutboundExcludeUrlPatterns()) {
                if (s != null && !s.isBlank()) {
                    raw.add(s.trim());
                }
            }
        }
        String digest = String.join("\u0000", raw);
        if (digest.equals(cacheDigest)) {
            return cachedPatterns;
        }
        synchronized (this) {
            if (digest.equals(cacheDigest)) {
                return cachedPatterns;
            }
            List<Pattern> compiled = new ArrayList<>(raw.size());
            for (String regex : raw) {
                try {
                    compiled.add(Pattern.compile(regex));
                } catch (PatternSyntaxException ignored) {
                    // Skip invalid user patterns rather than failing the whole strategy.
                }
            }
            cachedPatterns = List.copyOf(compiled);
            cacheDigest = digest;
            return cachedPatterns;
        }
    }

    private List<String> builtinPatterns() {
        List<String> out = new ArrayList<>(2);
        out.add(".*/actuator/faultinjector(/|\\?|$).*");
        String uiPath = normalizePath(properties.getUi().getPath());
        if (!uiPath.isEmpty()) {
            String escaped = Pattern.quote(uiPath);
            out.add(".*" + escaped + "(/|\\?|$).*");
        }
        return out;
    }

    private static String normalizePath(String configured) {
        if (configured == null || configured.isBlank()) {
            return "/fault-injector";
        }
        String p = configured.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
