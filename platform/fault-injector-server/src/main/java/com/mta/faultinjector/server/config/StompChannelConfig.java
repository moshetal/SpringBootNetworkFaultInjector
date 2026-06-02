package com.mta.faultinjector.server.config;

import com.mta.faultinjector.server.stomp.StompSessionPrincipalInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
public class StompChannelConfig implements WebSocketMessageBrokerConfigurer {

    private final StompSessionPrincipalInterceptor interceptor;

    public StompChannelConfig(StompSessionPrincipalInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(interceptor);
    }
}
