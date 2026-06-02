CREATE TABLE IF NOT EXISTS agent_instance (
    instance_id VARCHAR(255) PRIMARY KEY,
    service_name VARCHAR(255) NOT NULL,
    first_seen TIMESTAMPTZ NOT NULL,
    last_seen TIMESTAMPTZ NOT NULL,
    metadata JSONB
);

CREATE TABLE IF NOT EXISTS fault_event (
    id BIGSERIAL PRIMARY KEY,
    ts BIGINT NOT NULL,
    service_name VARCHAR(255) NOT NULL,
    instance_id VARCHAR(255) NOT NULL,
    rule_name VARCHAR(255),
    outcome VARCHAR(64),
    method VARCHAR(16),
    host VARCHAR(512),
    url TEXT,
    fault_type VARCHAR(32),
    delay_ms BIGINT,
    error_status INT
);
CREATE INDEX IF NOT EXISTS idx_fault_event_service_ts ON fault_event (service_name, ts DESC);
CREATE INDEX IF NOT EXISTS idx_fault_event_instance_ts ON fault_event (instance_id, ts DESC);

CREATE TABLE IF NOT EXISTS metric_bucket (
    id BIGSERIAL PRIMARY KEY,
    bucket_start BIGINT NOT NULL,
    service_name VARCHAR(255) NOT NULL,
    instance_id VARCHAR(255) NOT NULL,
    rule_name VARCHAR(255),
    matches BIGINT NOT NULL DEFAULT 0,
    triggers BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_metric_bucket_start_service ON metric_bucket (bucket_start, service_name);

CREATE TABLE IF NOT EXISTS resilience_retry_observation (
    id BIGSERIAL PRIMARY KEY,
    service_name VARCHAR(255) NOT NULL,
    instance_id VARCHAR(255) NOT NULL,
    rule_name VARCHAR(255),
    host VARCHAR(512),
    method VARCHAR(16),
    url_path TEXT,
    fault_epoch_ms BIGINT NOT NULL,
    window_ms BIGINT NOT NULL,
    observed_retries INT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_resilience_retry_service_epoch ON resilience_retry_observation (service_name, fault_epoch_ms DESC);

CREATE TABLE IF NOT EXISTS resilience_cb_observation (
    id BIGSERIAL PRIMARY KEY,
    service_name VARCHAR(255) NOT NULL,
    instance_id VARCHAR(255) NOT NULL,
    host VARCHAR(512),
    method VARCHAR(16),
    threshold INT NOT NULL,
    threshold_reached_at_ms BIGINT NOT NULL,
    window_ms BIGINT NOT NULL,
    post_window_call_count INT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_resilience_cb_service_epoch ON resilience_cb_observation (service_name, threshold_reached_at_ms DESC);

CREATE TABLE IF NOT EXISTS resilience_delay_observation (
    id BIGSERIAL PRIMARY KEY,
    ts BIGINT NOT NULL,
    service_name VARCHAR(255) NOT NULL,
    instance_id VARCHAR(255) NOT NULL,
    rule_name VARCHAR(255),
    host VARCHAR(512),
    method VARCHAR(16),
    injected_delay_ms BIGINT NOT NULL,
    observed_wait_ms BIGINT NOT NULL,
    completed_successfully BOOLEAN NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_resilience_delay_service_ts ON resilience_delay_observation (service_name, ts DESC);

CREATE TABLE IF NOT EXISTS command_audit (
    id BIGSERIAL PRIMARY KEY,
    command_type VARCHAR(64) NOT NULL,
    service_name VARCHAR(255) NOT NULL,
    scope VARCHAR(255) NOT NULL,
    applied INT NOT NULL,
    failed INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
