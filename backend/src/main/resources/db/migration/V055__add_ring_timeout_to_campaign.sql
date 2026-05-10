ALTER TABLE campaign
    ADD COLUMN ring_timeout_seconds INTEGER NOT NULL DEFAULT 30
    CONSTRAINT chk_campaign_ring_timeout CHECK (ring_timeout_seconds BETWEEN 15 AND 120);
