package com.mta.faultinjector.server.web;

import com.mta.faultinjector.server.config.ServerProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ConsoleResourceConfig implements WebMvcConfigurer {

    private static final String CLASSPATH = "classpath:/static/console/";

    private final String basePath;

    public ConsoleResourceConfig(ServerProperties properties) {
        String p = properties.getConsole().getPath();
        if (p == null || p.isBlank()) {
            p = "/console";
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        this.basePath = p;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(basePath + "/**")
                .addResourceLocations(CLASSPATH)
                .setCachePeriod(0);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController(basePath, basePath + "/");
        registry.addViewController(basePath + "/").setViewName("forward:" + basePath + "/index.html");
    }
}
