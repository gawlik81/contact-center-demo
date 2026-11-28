-- =============================================================================
-- V152__refresh_token_session_id.sql
-- BE-249 (EPIC-33): stable identifier of a login session ("device"), `sid`.
--
-- WHY: logout must close the WebSocket sessions of THIS device only (logoutAll = all devices), and a self-service
-- password change must keep the current device signed in while it revokes the others. After a token refresh the
-- WebSocket session may have been opened with an OLDER access token, so the hash of the current access token does not
-- identify the device. The refresh-token FAMILY does: a login starts a family, every rotation inherits it.
--
-- session_id = id of the family. It is copied to the `sid` claim of every access token issued for that family and is
-- stored in the registry entry of a WebSocket session at CONNECT. Deliberately a NEW, descriptively named column.
--
-- Existing ACTIVE rows (not revoked, not expired): each becomes its own family (session_id = token_id); the next
-- rotation inherits it, so currently signed-in devices keep working. Revoked/expired rows are not touched (they are
-- never used again; avoids a pointless rewrite of a large table). Access tokens issued before the deploy carry no
-- `sid` (short-lived, <= access TTL).
-- The column stays nullable: during a rolling deploy OLD instances still insert rows with session_id = NULL after this
-- migration ran; the application treats NULL as "family = token_id" (COALESCE(session_id, token_id)) everywhere.
-- =============================================================================

ALTER TABLE refresh_token ADD COLUMN IF NOT EXISTS session_id UUID;

UPDATE refresh_token SET session_id = token_id
WHERE session_id IS NULL AND is_revoked = FALSE AND expires_at > NOW();

COMMENT ON COLUMN refresh_token.session_id IS
    'Login-session (device) id = refresh-token family; inherited on rotation; claim sid of the access token (BE-249).';
