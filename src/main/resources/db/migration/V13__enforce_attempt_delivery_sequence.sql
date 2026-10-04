-- Fail visibly on historical sequence damage. This audit proves contiguity only at migration time;
-- ongoing 1..N allocation is the application transaction's responsibility.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM attempts GROUP BY delivery_id, attempt_no HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'P05 migration blocked: duplicate (delivery_id, attempt_no); run the documented duplicate audit';
    END IF;

    IF EXISTS (SELECT 1 FROM attempts WHERE attempt_no < 1) THEN
        RAISE EXCEPTION 'P05 migration blocked: non-positive attempt_no; run the documented history audit';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM attempts
        GROUP BY delivery_id
        HAVING MIN(attempt_no) <> 1 OR MAX(attempt_no) <> COUNT(*)
    ) THEN
        RAISE EXCEPTION 'P05 migration blocked: non-contiguous Delivery attempt history; run the documented history audit';
    END IF;
END $$;

ALTER TABLE attempts
    ADD CONSTRAINT attempts_attempt_no_positive CHECK (attempt_no >= 1),
    ADD CONSTRAINT uk_attempts_delivery_attempt_no UNIQUE (delivery_id, attempt_no);

-- PostgreSQL 16 uses the new unique B-tree backward for the descending latest-Attempt lookup.
DROP INDEX idx_attempts_delivery_attempt_no;
