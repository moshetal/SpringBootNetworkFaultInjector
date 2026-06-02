package com.mta.faultinjection.protocol;

/** Command types mirrored from the local UI REST API. */
public enum CommandType {
    SET_ENABLED,
    UPDATE_DEFAULTS,
    ADD_RULE,
    UPDATE_RULE,
    DELETE_RULE,
    SET_RULE_ENABLED,
    RESET_METRICS,
    GET_CONFIG
}
