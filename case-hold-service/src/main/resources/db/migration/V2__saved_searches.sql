-- Saved searches belong to a case, not to the search service.
--
-- A saved search is a case artefact: it records how a reviewer scoped the
-- evidence for a matter, and it should live and die with the case. Storing it
-- here also means it inherits the ON DELETE CASCADE below, so closing out a
-- case cannot leave orphaned criteria behind in another service's store.
--
-- The criteria are kept as JSON rather than as columns. This service does not
-- interpret them; it hands them back to the client, which replays them against
-- the search API. Modelling every filter as a column would couple the case
-- schema to the search service's query parameters, so adding a filter there
-- would need a migration here.
CREATE TABLE IF NOT EXISTS saved_searches (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id     UUID NOT NULL,
    name        VARCHAR(255) NOT NULL,
    criteria    TEXT NOT NULL,
    created_by  VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_saved_searches_case FOREIGN KEY (case_id) REFERENCES cases(id) ON DELETE CASCADE,
    CONSTRAINT uq_saved_searches_name UNIQUE (case_id, name)
);

CREATE INDEX IF NOT EXISTS idx_saved_searches_case_id ON saved_searches (case_id);
