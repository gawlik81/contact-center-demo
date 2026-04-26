-- Optimistic locking: dodanie kolumny version do encji z wysoką konkurencją zapisu
-- Używana przez Hibernate @Version do wykrywania konfliktów równoczesnych modyfikacji.

ALTER TABLE app_user ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE queue ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE campaign ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE customer ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
