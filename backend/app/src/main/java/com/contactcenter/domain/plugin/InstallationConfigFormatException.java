package com.contactcenter.domain.plugin;

/**
 * The stored {@code installation_config} wrapper ({@code {"encrypted": "..."}}) is malformed (BE-245 W3). A
 * single row problem (as opposed to a failed decryption, which points at the key), so it is skipped on its own.
 */
class InstallationConfigFormatException extends IllegalStateException {

    InstallationConfigFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
