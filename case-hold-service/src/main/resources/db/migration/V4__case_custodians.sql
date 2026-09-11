CREATE TABLE IF NOT EXISTS case_custodians (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES cases(id) ON DELETE CASCADE,
    custodian_id VARCHAR(255) NOT NULL,
    added_by VARCHAR(255) NOT NULL,
    added_at TIMESTAMP NOT NULL,
    UNIQUE (case_id, custodian_id)
);

CREATE INDEX IF NOT EXISTS idx_case_custodians_case_id ON case_custodians (case_id);
CREATE INDEX IF NOT EXISTS idx_case_custodians_custodian_id ON case_custodians (custodian_id);
