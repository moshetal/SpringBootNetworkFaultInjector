package com.mta.faultinjection.autoconfig;

import com.mta.faultinjection.config.FaultInjectionProperties;
import com.mta.faultinjection.config.FaultInjectionProperties.Ui;
import com.mta.faultinjection.core.FaultDecisionStrategy;
import com.mta.faultinjection.telemetry.FaultInjectionTelemetry;
import com.mta.faultinjection.web.FaultInjectorLocalhostOnlyFilter;
import com.mta.faultinjection.web.FaultInjectorUiController;
import com.mta.faultinjection.web.FaultInjectorUiResourceConfig;
import com.mta.faultinjection.web.FaultInjectorUiService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Wires the bundled, "Swagger-style" UI for the fault-injection starter.
 * <p>
 * The UI ships as static assets ({@code /static/fault-injector-ui/}) plus a
 * REST controller ({@link FaultInjectorUiController}) that the static page
 * polls. It is enabled by default whenever Spring MVC is on the classpath, and
 * can be opted out with {@code fault.injection.ui.enabled=false}.
 * <p>
 * Runs <em>before</em> {@link FaultInjectionAutoConfiguration} so the
 * {@link FaultInjectionTelemetry} bean is present when the strategy is
 * constructed; otherwise the strategy would resolve {@code null} from the
 * {@code ObjectProvider} and never wire telemetry into recorded decisions.
 */
@AutoConfiguration
@AutoConfigureBefore(FaultInjectionAutoConfiguration.class)
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnProperty(prefix = "fault.injection.ui", name = "enabled", matchIfMissing = true)
public class FaultInjectionUiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FaultInjectionTelemetry faultInjectionTelemetry(FaultInjectionProperties properties) {
        Ui ui = properties.getUi();
        long bucketWidthMs = Math.max(1, ui.getTimeseriesBucketSeconds()) * 1000L;
        return new FaultInjectionTelemetry(ui.getEventBufferSize(), bucketWidthMs, ui.getTimeseriesBuckets());
    }

    @Bean
    @ConditionalOnMissingBean
    public FaultInjectorUiService faultInjectorUiService(
            FaultInjectionProperties properties,
            FaultDecisionStrategy strategy,
            FaultInjectionTelemetry telemetry,
            Environment environment) {
        return new FaultInjectorUiService(properties, strategy, telemetry, environment);
    }

    @Bean
    @ConditionalOnMissingBean
    public FaultInjectorUiController faultInjectorUiController(FaultInjectorUiService uiService) {
        return new FaultInjectorUiController(uiService);
    }

    @Bean
    @ConditionalOnProperty(prefix = "fault.injection.ui", name = "require-localhost", havingValue = "true")
    public FilterRegistrationBean<FaultInjectorLocalhostOnlyFilter> faultInjectorLocalhostOnlyFilter(
            FaultInjectionProperties properties) {
        FilterRegistrationBean<FaultInjectorLocalhostOnlyFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new FaultInjectorLocalhostOnlyFilter(properties));
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
        reg.addUrlPatterns(p + "/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return reg;
    }

    @Bean
    @ConditionalOnMissingBean
    public FaultInjectorUiResourceConfig faultInjectorUiResourceConfig(FaultInjectionProperties properties) {
        return new FaultInjectorUiResourceConfig(properties);
    }
}
