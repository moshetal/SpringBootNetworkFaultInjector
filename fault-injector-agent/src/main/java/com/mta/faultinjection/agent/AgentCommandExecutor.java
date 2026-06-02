package com.mta.faultinjection.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mta.faultinjection.protocol.CommandEnvelope;
import com.mta.faultinjection.protocol.CommandType;
import com.mta.faultinjection.web.FaultInjectorControlFacade;
import com.mta.faultinjection.web.FaultInjectorUiDtos;
import com.mta.faultinjection.web.FaultInjectorUiRequestException;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;

/** Executes server commands against the local {@link FaultInjectorControlFacade}. */
public class AgentCommandExecutor {

    private final FaultInjectorControlFacade facade;
    private final ObjectMapper mapper;

    public AgentCommandExecutor(FaultInjectorControlFacade facade, ObjectMapper mapper) {
        this.facade = Objects.requireNonNull(facade, "facade");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public ExecutionResult execute(CommandEnvelope command) {
        try {
            Object result = switch (command.type()) {
                case SET_ENABLED -> facade.setEnabled(read(command.payload(), FaultInjectorUiDtos.EnabledDto.class));
                case UPDATE_DEFAULTS -> facade.updateDefaults(read(command.payload(), FaultInjectorUiDtos.DefaultsDto.class));
                case ADD_RULE -> facade.addRule(read(command.payload(), FaultInjectorUiDtos.RuleDto.class));
                case UPDATE_RULE -> {
                    String name = requireText(command.payload(), "name");
                    yield facade.updateRule(name, read(command.payload(), FaultInjectorUiDtos.RuleDto.class));
                }
                case DELETE_RULE -> facade.deleteRule(requireText(command.payload(), "name"));
                case SET_RULE_ENABLED -> {
                    String name = requireText(command.payload(), "name");
                    yield facade.setRuleEnabled(name, read(command.payload(), FaultInjectorUiDtos.EnabledDto.class));
                }
                case RESET_METRICS -> facade.resetMetrics(read(command.payload(), FaultInjectorUiDtos.ResetDto.class));
                case GET_CONFIG -> facade.config();
            };
            return ExecutionResult.ok(result);
        } catch (FaultInjectorUiRequestException ex) {
            return ExecutionResult.fail(ex.getMessage());
        } catch (RuntimeException ex) {
            return ExecutionResult.fail(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    private <T> T read(JsonNode payload, Class<T> type) {
        if (payload == null || payload.isNull()) {
            if (type == FaultInjectorUiDtos.ResetDto.class) {
                @SuppressWarnings("unchecked")
                T empty = (T) new FaultInjectorUiDtos.ResetDto();
                return empty;
            }
            throw new FaultInjectorUiRequestException(HttpStatus.BAD_REQUEST, "payload is required");
        }
        return mapper.convertValue(payload, type);
    }

    private static String requireText(JsonNode payload, String field) {
        if (payload == null || !payload.hasNonNull(field)) {
            throw new FaultInjectorUiRequestException(HttpStatus.BAD_REQUEST, "'" + field + "' is required");
        }
        return payload.get(field).asText();
    }

    public record ExecutionResult(boolean success, JsonNode payload, String error) {
        static ExecutionResult ok(Object body) {
            ObjectMapper m = new ObjectMapper();
            return new ExecutionResult(true, m.valueToTree(body), null);
        }

        static ExecutionResult fail(String error) {
            return new ExecutionResult(false, null, error);
        }
    }
}
