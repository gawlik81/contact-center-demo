-- Add OFFLINE to the app_user status check constraint
ALTER TABLE app_user
    DROP CONSTRAINT chk_app_user_status;

ALTER TABLE app_user
    ADD CONSTRAINT chk_app_user_status
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'AVAILABLE', 'BUSY', 'BREAK', 'AFTER_CONTACT', 'OFFLINE'));
