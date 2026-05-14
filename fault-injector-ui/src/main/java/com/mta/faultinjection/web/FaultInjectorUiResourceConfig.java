package com.mta.faultinjection.web;

import com.mta.faultinjection.config.FaultInjectionProperties;
import java.util.Objects;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Mounts the bundled UI's static assets under {@code ${fault.injection.ui.path}}
 * and forwards the bare prefix to {@code index.html}.
 * <p>
 * The asset bundle ships as classpath resources under
 * {@code /static/fault-injector-ui/}. We could rely on Spring Boot's default
 * static-resource handler at {@code /**}, but that hard-codes the URL prefix to
 * the directory name. Routing through a dedicated handler keeps the prefix
 * configurable. Registered as a bean by {@code FaultInjectionUiAutoConfiguration};
 * Spring MVC discovers all {@link WebMvcConfigurer} beans automatically.
 */
public class FaultInjectorUiResourceConfig implements WebMvcConfigurer {

    private static final String CLASSPATH_LOCATION = "classpath:/static/fault-injector-ui/";

    private final String basePath;

    public FaultInjectorUiResourceConfig(FaultInjectionProperties properties) {
        Objects.requireNonNull(properties, "properties");
        String configured = properties.getUi().getPath();
        if (configured == null || configured.isBlank()) {
            configured = "/fault-injector";
        }
        if (!configured.startsWith("/")) {
            configured = "/" + configured;
        }
        if (configured.endsWith("/") && configured.length() > 1) {
            configured = configured.substring(0, configured.length() - 1);
        }
        this.basePath = configured;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(basePath + "/**")
                .addResourceLocations(CLASSPATH_LOCATION)
                .setCachePeriod(0); // 'no cache' so config edits aren't masked by stale assets
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Without a trailing slash the browser resolves the page's relative
        // asset URLs (`./app.js`, `./styles.css`) against the parent directory,
        // which means they hit the application root instead of the UI prefix
        // and 404 — leaving the page styled but with no JavaScript wired up.
        // Redirect to the slash variant so the base URL is right, then forward
        // to index.html.
        registry.addRedirectViewController(basePath, basePath + "/");
        registry.addViewController(basePath + "/").setViewName("forward:" + basePath + "/index.html");
    }

    /** Exposed for tests so they can assert the resolved prefix. */
    public String getBasePath() {
        return basePath;
    }
}
