package com.mta.faultinjection.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mta.faultinjection.agent.AgentCommandExecutor;
import com.mta.faultinjection.agent.FaultInjectorStompAgent;
import com.mta.faultinjection.config.FaultInjectionProperties;
import com.mta.faultinjection.web.FaultInjectorControlFacade;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.socket.client.WebSocketClient;

@AutoConfiguration(after = FaultInjectionUiAutoConfiguration.class)
@ConditionalOnClass(WebSocketClient.class)
@ConditionalOnProperty(prefix = "fault.injection.agent", name = "enabled", havingValue = "true")
public class FaultInjectionAgentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AgentCommandExecutor agentCommandExecutor(
            FaultInjectorControlFacade facade, ObjectMapper mapper) {
        return new AgentCommandExecutor(facade, mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public FaultInjectorStompAgent faultInjectorStompAgent(
            FaultInjectionProperties properties,
            FaultInjectorControlFacade facade,
            AgentCommandExecutor commandExecutor,
            ObjectMapper mapper,
            Environment environment) {
        return new FaultInjectorStompAgent(properties, facade, commandExecutor, mapper, environment);
    }
}
