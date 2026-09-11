-- Remove ARCHIVED from the case lifecycle, leaving OPEN and CLOSED.
--
-- ARCHIVED was a third status that only ever did one thing: make the service
-- refuse holds, evidence and saved searches on the case. That made it the only
-- server-enforced lock in the schema while CLOSED — which the UI already
-- presented as read-only — was enforced nowhere. Two statuses meaning
-- "finished", one of them enforced and the other not, is a distinction nobody
-- could act on, so the concept is gone rather than half-used.
--
-- Existing rows are folded into CLOSED. That is the closest surviving meaning:
-- the matter is finished. It has to run before the constraint is replaced, or
-- the new CHECK would be rejected by rows it cannot accept.
UPDATE cases SET status = 'CLOSED', updated_at = now() WHERE status = 'ARCHIVED';

-- Recreate the constraint without ARCHIVED. Dropping first is required because
-- Postgres has no ALTER ... CHECK, and IF EXISTS keeps the migration safe on a
-- database where V1 was applied before the constraint was named.
ALTER TABLE cases DROP CONSTRAINT IF EXISTS chk_cases_status;

ALTER TABLE cases
    ADD CONSTRAINT chk_cases_status CHECK (status IN ('OPEN', 'CLOSED'));
