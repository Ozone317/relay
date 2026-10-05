-- V13's unique Delivery/ordinal index is the correctness backstop. This mixed-order
-- index serves the existing delivery_status list/count read pattern, not uniqueness.
CREATE INDEX idx_attempts_delivery_attempt_no
    ON attempts (delivery_id, attempt_no DESC);
