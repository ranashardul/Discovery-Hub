-- Initial schema for the Case & Hold Service.
--
-- Cases are legal/business investigations. Holds preserve communications
-- associated with a case from deletion while the hold is active. Both tables
-- store only references to communications; the message bodies live in the
-- ingestion/search data layer and are never duplicated here.
--
-- Domain events are written to the event_outbox table in the same transaction
-- as the change they describe, then published to Kafka asynchronously by the
-- OutboxPublisher. This gives at-least-once delivery without a distributed
-- transaction.

CREATE TABLE IF NOT EXISTS cases (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(255) NOT NULL,
    description   TEXT,
    status        VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    created_by    VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_cases_status CHECK (status IN ('OPEN', 'CLOSED', 'ARCHIVED'))
);

CREATE INDEX IF NOT EXISTS idx_cases_status     ON cases (status);
CREATE INDEX IF NOT EXISTS idx_cases_created_by ON cases (created_by);

CREATE TABLE IF NOT EXISTS holds (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id       UUID NOT NULL,
    name          VARCHAR(255) NOT NULL,
    description   TEXT,
    reason        TEXT,
    status        VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    scope         VARCHAR(32) NOT NULL DEFAULT 'COMMUNICATION',
    -- Criteria-based hold rules. Null when scope = COMMUNICATION. Lists are
    -- stored comma-separated because participants (emails) and communication
    -- types never contain commas, which keeps the mapping free of array or
    -- JSON column-type handling.
    criteria_participants           TEXT,
    criteria_communication_types    TEXT,
    criteria_from_date              TIMESTAMPTZ,
    criteria_to_date                TIMESTAMPTZ,
    created_by    VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_by   VARCHAR(255),
    released_at   TIMESTAMPTZ,
    CONSTRAINT fk_holds_case    FOREIGN KEY (case_id) REFERENCES cases(id) ON DELETE CASCADE,
    CONSTRAINT chk_holds_status CHECK (status IN ('ACTIVE', 'RELEASED')),
    CONSTRAINT chk_holds_scope  CHECK (scope IN ('COMMUNICATION', 'CRITERIA'))
);

CREATE INDEX IF NOT EXISTS idx_holds_case_id ON holds (case_id);
CREATE INDEX IF NOT EXISTS idx_holds_status  ON holds (status);

CREATE TABLE IF NOT EXISTS case_communications (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id            UUID NOT NULL,
    communication_id   VARCHAR(255) NOT NULL,
    communication_type VARCHAR(64),
    added_by           VARCHAR(255),
    added_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_case_comms_case FOREIGN KEY (case_id) REFERENCES cases(id) ON DELETE CASCADE,
    CONSTRAINT uq_case_comms      UNIQUE (case_id, communication_id)
);

CREATE INDEX IF NOT EXISTS idx_case_comms_case_id ON case_communications (case_id);
CREATE INDEX IF NOT EXISTS idx_case_comms_comm_id ON case_communications (communication_id);

CREATE TABLE IF NOT EXISTS hold_communications (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hold_id            UUID NOT NULL,
    communication_id   VARCHAR(255) NOT NULL,
    communication_type VARCHAR(64),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_hold_comms_hold FOREIGN KEY (hold_id) REFERENCES holds(id) ON DELETE CASCADE,
    CONSTRAINT uq_hold_comms      UNIQUE (hold_id, communication_id)
);

CREATE INDEX IF NOT EXISTS idx_hold_comms_hold_id ON hold_communications (hold_id);
CREATE INDEX IF NOT EXISTS idx_hold_comms_comm_id ON hold_communications (communication_id);

CREATE TABLE IF NOT EXISTS event_outbox (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type    VARCHAR(64) NOT NULL,
    aggregate_id  VARCHAR(64) NOT NULL,
    payload       TEXT NOT NULL,
    status        VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    attempts      INTEGER NOT NULL DEFAULT 0,
    last_error    TEXT,
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED'))
);

-- Partial index keeps the publisher scan cheap: it only walks unpublished rows.
CREATE INDEX IF NOT EXISTS idx_event_outbox_pending
    ON event_outbox (created_at)
    WHERE status = 'PENDING';
