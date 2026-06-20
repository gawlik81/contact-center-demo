package com.contactcenter.api.plugin;

import com.contactcenter.domain.plugin.PluginStorageService;
import com.contactcenter.domain.plugin.PluginValidationService;
import com.contactcenter.domain.plugin.dto.PluginVersionDto;
import com.contactcenter.domain.plugin.dto.ValidationResult;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link PluginUploadController} (BE-099).
 *
 * <p>Weryfikuje logikę kontrolera bez uruchomionej infrastruktury Spring MVC — wzorzec
 * analogiczny do {@code RecordingControllerTest}: wywołanie metod kontrolera bezpośrednio,
 * {@code TenantContext} mockowany statycznie (symuluje filtr JWT).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PluginUploadController – upload JAR-a do globalnego katalogu pluginów")
class PluginUploadControllerTest {

    private static final UUID UPLOADED_BY = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    @Mock private PluginValidationService pluginValidationService;
    @Mock private PluginStorageService pluginStorageService;

    private PluginUploadController controller;
    private MockedStatic<TenantContext> tenantContextMock;

    @BeforeEach
    void setUp() {
        controller = new PluginUploadController(pluginValidationService, pluginStorageService);
        tenantContextMock = mockStatic(TenantContext.class);
        tenantContextMock.when(TenantContext::getUserId).thenReturn(UPLOADED_BY);
    }

    @AfterEach
    void tearDown() {
        tenantContextMock.close();
    }

    private MockMultipartFile validJarFile() {
        return new MockMultipartFile("file", "acme-crm-sync-1.3.0.jar",
                "application/java-archive", "fake jar bytes".getBytes());
    }

    // =========================================================================
    // Happy path
    // =========================================================================

    @Nested
    @DisplayName("POST /api/supervisor/plugins")
    class UploadPlugin {

        @Test
        @DisplayName("powinien zwrócić 201 z PluginVersionDto gdy walidacja przejdzie")
        void shouldReturn201WhenValidated() throws Exception {
            MockMultipartFile file = validJarFile();
            ValidationResult validated = ValidationResult.validated();
            PluginVersionDto expectedDto = new PluginVersionDto(
                    UUID.randomUUID(), UUID.randomUUID(), "acme-crm-sync", "1.3.0", "1.x",
                    "VALIDATED", List.of(), UPLOADED_BY, Instant.now());

            when(pluginValidationService.validate(file.getBytes(), UPLOADED_BY)).thenReturn(validated);
            when(pluginStorageService.storeValidatedJar(file.getBytes(), file.getOriginalFilename(),
                    validated, UPLOADED_BY)).thenReturn(expectedDto);

            ResponseEntity<?> response = controller.uploadPlugin(file);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody()).isEqualTo(expectedDto);
        }

        @Test
        @DisplayName("powinien zwrócić 400 z validationErrors gdy walidacja odrzuci JAR, bez wywołania storage")
        void shouldReturn400WhenRejected() throws Exception {
            MockMultipartFile file = validJarFile();
            ValidationResult rejected = ValidationResult.rejected(
                    List.of("Checksum mismatch", "Nieznany extensionPoint"));

            when(pluginValidationService.validate(file.getBytes(), UPLOADED_BY)).thenReturn(rejected);

            ResponseEntity<?> response = controller.uploadPlugin(file);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(pluginStorageService, never()).storeValidatedJar(any(), any(), any(), any());
        }

        @Test
        @DisplayName("powinien zwrócić 400 i NIE wywołać walidacji gdy plik przekracza 50MB")
        void shouldReturn400ForOversizedFileWithoutCallingValidation() throws Exception {
            byte[] oversized = new byte[(int) (50L * 1024 * 1024) + 1];
            MockMultipartFile file = new MockMultipartFile("file", "huge.jar",
                    "application/java-archive", oversized);

            ResponseEntity<?> response = controller.uploadPlugin(file);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(pluginValidationService, never()).validate(any(), any());
            verify(pluginStorageService, never()).storeValidatedJar(any(), any(), any(), any());
        }

        @Test
        @DisplayName("powinien zwrócić 400 dla pustego pliku")
        void shouldReturn400ForEmptyFile() throws Exception {
            MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.jar",
                    "application/java-archive", new byte[0]);

            ResponseEntity<?> response = controller.uploadPlugin(emptyFile);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(pluginValidationService, never()).validate(any(), any());
        }

        @Test
        @DisplayName("powinien przekazać currentUserId z TenantContext do walidacji i storage")
        void shouldPassCurrentUserIdFromTenantContext() throws Exception {
            MockMultipartFile file = validJarFile();
            ValidationResult validated = ValidationResult.validated();
            PluginVersionDto dto = new PluginVersionDto(
                    UUID.randomUUID(), UUID.randomUUID(), "acme-crm-sync", "1.3.0", "1.x",
                    "VALIDATED", List.of(), UPLOADED_BY, Instant.now());

            when(pluginValidationService.validate(any(), eq(UPLOADED_BY))).thenReturn(validated);
            when(pluginStorageService.storeValidatedJar(any(), any(), any(), eq(UPLOADED_BY))).thenReturn(dto);

            controller.uploadPlugin(file);

            verify(pluginValidationService).validate(any(), eq(UPLOADED_BY));
            verify(pluginStorageService).storeValidatedJar(any(), any(), any(), eq(UPLOADED_BY));
        }
    }
}
