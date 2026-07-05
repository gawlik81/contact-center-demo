package com.contactcenter.api;

import com.contactcenter.api.customer.CustomerImportController;
import com.contactcenter.api.customer.DeduplicationMode;
import com.contactcenter.domain.customer.CustomerImportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test MockMvc dla {@link CustomerImportController} (BE-026) — regresja wiązania parametrów.
 *
 * <p>W odróżnieniu od pozostałych testów kontrolerów w tym pakiecie (np. {@code CustomerControllerTest}),
 * które wywołują metody kontrolera bezpośrednio, ten test celowo wysyła prawdziwe żądanie HTTP
 * przez {@link MockMvc}. Cel: zweryfikować RZECZYWISTE wiązanie {@code @RequestParam} przez Spring
 * po nazwie parametru zapytania HTTP — wywołanie metody Javy bezpośrednio (jak w pozostałych testach
 * tego pakietu) nie wykryłoby tej regresji, bo pomija cały mechanizm wiązania parametrów.
 *
 * <p><strong>Odkryty bug (przedistniejący, niezwiązany z externalId):</strong> frontend
 * ({@code customer.service.ts#importCsv}) wysyła parametry zapytania {@code separator} i
 * {@code deduplication}, podczas gdy kontroler miał zmienne Javy {@code columnSeparator} i
 * {@code deduplicationMode} bez jawnego {@code name=} w {@code @RequestParam}. Spring wiąże
 * {@code @RequestParam} po nazwie zmiennej Javy gdy {@code name} nie jest podane — oba parametry
 * były więc cicho ignorowane i zawsze przyjmowały wartość domyślną (SKIP / przecinek) niezależnie
 * od wyboru użytkownika w UI. Efekt: import CSV rozdzielanego średnikami parsował całą linię jako
 * jedno pole, kolumna {@code phone} nie istniała → każdy wiersz odrzucany jako brak nr E.164.
 *
 * <p>{@code @AutoConfigureMockMvc(addFilters = false)} wyłącza łańcuch filtrów Spring Security
 * (JwtAuthFilter/TenantFilter) — nie są przedmiotem tego testu; autoryzacja {@code @PreAuthorize}
 * jest weryfikowana deklaratywnie przez code review (wzorzec analogiczny do
 * {@code PluginAdminControllerTest}/{@code PluginAgentControllerTest} — projekt nie ma
 * {@code @WebMvcTest} z pełnym łańcuchem security dla zwykłych kontrolerów biznesowych).
 * Kontroler i tak nie odczytuje {@code TenantContext} bezpośrednio — robi to dopiero
 * {@code CustomerImportServiceImpl} (tutaj zamockowany jako {@link CustomerImportService}).
 *
 * <p>Kontekst bootstrapowany przez lokalną {@link MinimalBootConfig} (nie
 * {@code ContactCenterApplication}) — {@code ContactCenterApplication} leży w pakiecie
 * {@code com.contactcenter.app}, więc automatyczne wyszukiwanie {@code @SpringBootConfiguration}
 * w hierarchii pakietów testu ({@code com.contactcenter.api}) i tak by go nie znalazło; ponadto
 * ma {@code @EnableJpaRepositories}/{@code @EntityScan} wymagające realnego
 * {@code EntityManagerFactory} (wzorzec analogiczny do {@code PluginUiSdkStaticAssetIT}).
 */
@WebMvcTest(controllers = CustomerImportController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("CustomerImportController – wiązanie parametrów POST /api/customers/import")
class CustomerImportControllerTest {

    /**
     * Minimalna klasa rozruchowa – bez {@code @EnableJpaRepositories}/{@code @EntityScan}.
     *
     * <p>Jawny {@code @Import(CustomerImportController.class)} jest wymagany: {@code @EnableAutoConfiguration}
     * (w odróżnieniu od {@code @SpringBootApplication}) sam w sobie NIE skanuje żadnego pakietu
     * w poszukiwaniu kontrolerów, więc mimo atrybutu {@code controllers=} w {@code @WebMvcTest}
     * kontroler nie trafiał do kontekstu (zweryfikowano: request kończył się 404 z
     * {@code ResourceHttpRequestHandler}, bo brak zarejestrowanego mappingu). Zwykły
     * {@code @ComponentScan(basePackageClasses = ...)} też nie jest dobrym rozwiązaniem: skanuje
     * BEZ filtra {@code WebMvcTypeExcludeFilter}, więc wciąga też sąsiedni {@code CustomerController}
     * z tego samego pakietu (i jego niezamockowane zależności) — {@code @Import} rejestruje
     * WYŁĄCZNIE wskazaną klasę.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(CustomerImportController.class)
    static class MinimalBootConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CustomerImportService customerImportService;

    private MockMultipartFile csvFile(String content) {
        return new MockMultipartFile("file", "customers.csv", "text/csv", content.getBytes());
    }

    @Test
    @DisplayName("KRYTERIUM REGRESJI: separator=; i deduplication=OVERWRITE docierają do serwisu z tymi wartościami")
    void importCustomers_bindsFrontendParamNames_toServiceCall() throws Exception {
        // given
        UUID jobId = UUID.randomUUID();
        when(customerImportService.initiateImport(any(), any(), any(), any(), any())).thenReturn(jobId);

        MockMultipartFile file = csvFile("first_name;last_name;phone\nJan;Kowalski;+48501234567\n");

        // when – żądanie identyczne z tym, co wysyła frontend (customer.service.ts#importCsv)
        mockMvc.perform(multipart("/api/customers/import")
                        .file(file)
                        .param("separator", ";")
                        .param("deduplication", "OVERWRITE")
                        .param("quoteChar", "'"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()));

        // then – NIE wartości domyślne, tylko to co przyszło w query params
        verify(customerImportService).initiateImport(
                any(), eq(DeduplicationMode.OVERWRITE), eq(";"), eq("'"), any());
    }

    @Test
    @DisplayName("bez parametrów zapytania – używa wartości domyślnych (SKIP, przecinek, cudzysłów)")
    void importCustomers_noQueryParams_usesDefaults() throws Exception {
        // given
        UUID jobId = UUID.randomUUID();
        when(customerImportService.initiateImport(any(), any(), any(), any(), any())).thenReturn(jobId);

        MockMultipartFile file = csvFile("first_name,last_name,phone\nJan,Kowalski,+48501234567\n");

        // when
        mockMvc.perform(multipart("/api/customers/import").file(file))
                .andExpect(status().isAccepted());

        // then
        verify(customerImportService).initiateImport(
                any(), eq(DeduplicationMode.SKIP), eq(","), eq("\""), any());
    }
}
