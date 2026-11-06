package com.contactcenter.api;

import com.contactcenter.api.retention.CampaignArchiveSettingController;
import com.contactcenter.domain.retention.CampaignArchiveSettingService;
import com.contactcenter.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-163: MockMvc dla mapowania pozostalych wyjatkow Spring MVC w {@link GlobalExceptionHandler}
 * (status + {@code application/problem+json} + brak wyciekow szczegolow wewnetrznych) oraz
 * regresje dla istniejacych mapowan (BE-161 404/405, ResponseStatusException, walidacja, catch-all 500,
 * lokalny 422 w {@link CampaignArchiveSettingController}).
 */
@WebMvcTest(controllers = {
        GlobalExceptionHandlerMvcTest.ProbeController.class,
        CampaignArchiveSettingController.class})
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("GlobalExceptionHandler - MockMvc (BE-163)")
class GlobalExceptionHandlerMvcTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({ProbeController.class, CampaignArchiveSettingController.class, GlobalExceptionHandler.class})
    static class MinimalBootConfig {
    }

    private static final String GENERIC_DETAIL =
            "Wystąpił nieoczekiwany błąd. Spróbuj ponownie lub skontaktuj się z administratorem.";

    record Body(@NotBlank String name) {
    }

    @RestController
    @RequestMapping("/probe")
    static class ProbeController {
        @GetMapping("/param")
        String param(@RequestParam String x) {
            return x;
        }

        @GetMapping("/header")
        String header(@RequestHeader("X-Required") String h) {
            return h;
        }

        @PostMapping(value = "/part", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        String part(@RequestPart("file") MultipartFile file) {
            return file.getName();
        }

        @GetMapping("/cookie")
        String cookie(@CookieValue("sid") String sid) {
            return sid;
        }

        // no {id} in the mapping -> MissingPathVariableException (server-side bug, 500)
        @GetMapping("/pathvar")
        String pathVar(@PathVariable String id) {
            return id;
        }

        @GetMapping("/binding")
        String binding() throws ServletRequestBindingException {
            throw new ServletRequestBindingException("secret internal com.contactcenter.Foo");
        }

        @PostMapping(value = "/json", consumes = MediaType.APPLICATION_JSON_VALUE)
        String json(@Valid @RequestBody Body body) {
            return body.name();
        }

        @GetMapping(value = "/produces", produces = MediaType.APPLICATION_JSON_VALUE)
        String produces() {
            return "{}";
        }

        @GetMapping("/validated")
        String validated(@RequestParam @Min(1) int n) {
            return "ok";
        }

        @GetMapping("/upload-too-big")
        String uploadTooBig() {
            throw new MaxUploadSizeExceededException(1024L);
        }

        @GetMapping("/async-timeout")
        String asyncTimeout() {
            throw new AsyncRequestTimeoutException();
        }

        @GetMapping("/rse")
        String rse() {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Brak nagrania");
        }

        @GetMapping("/business")
        String business() {
            throw new com.contactcenter.domain.exception.BusinessValidationException("Email jest już zajęty");
        }

        @GetMapping("/iae")
        String iae() {
            throw new IllegalArgumentException("Could not resolve attribute 'secretField' of com.contactcenter.Foo");
        }

        @GetMapping("/enum-valueof")
        String enumValueOf(@RequestParam String v) {
            return Thread.State.valueOf(v).name();
        }

        @GetMapping("/enum-valueof-app")
        String enumValueOfApp(@RequestParam String v) {
            return Enum.valueOf(com.contactcenter.domain.retention.RetentionDataCategory.class, v).name();
        }

        @GetMapping("/uuid-fromstring")
        String uuidFromString(@RequestParam String v) {
            return UUID.fromString(v).toString();
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("SQL error: table secret_table");
        }
    }

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CampaignArchiveSettingService archiveService;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_A);
        TenantContext.setUserId(USER);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Asserts problem+json, status field, message==detail and absence of internals in the body. */
    private static void assertProblem(ResultActions r, int status) throws Exception {
        r.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.type").isNotEmpty())
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
        String body = r.andReturn().getResponse().getContentAsString();
        for (String forbidden : new String[]{"Exception", "org.springframework", "com.contactcenter",
                "com.fasterxml", "jakarta.", "java.lang", "secret", "stackTrace", "\tat "}) {
            org.assertj.core.api.Assertions.assertThat(body).doesNotContain(forbidden);
        }
    }

    @Test
    @DisplayName("415 - nieobslugiwany Content-Type")
    void unsupportedMediaType() throws Exception {
        ResultActions r = mockMvc.perform(post("/probe/json").contentType(MediaType.TEXT_PLAIN).content("x"));
        assertProblem(r, 415);
        r.andExpect(header().exists("Accept"))
                .andExpect(jsonPath("$.type").value("https://contactcenter.io/errors/unsupported-media-type"));
    }

    @Test
    @DisplayName("406 - klient odrzuca JSON w Accept -> nadal 406 problem+json (nie 500)")
    void notAcceptable() throws Exception {
        ResultActions r = mockMvc.perform(get("/probe/produces").accept(MediaType.APPLICATION_XML));
        assertProblem(r, 406);
        r.andExpect(header().string("Accept", org.hamcrest.Matchers.containsString("application/json")))
                .andExpect(jsonPath("$.title").value("Nieobsługiwany format odpowiedzi"))
                .andExpect(jsonPath("$.detail").value("Żądany format odpowiedzi nie jest obsługiwany."))
                .andExpect(jsonPath("$.type").value("https://contactcenter.io/errors/not-acceptable"));
    }

    @Test
    @DisplayName("400 - brak wymaganego parametru (nazwa parametru w detail)")
    void missingParameter() throws Exception {
        ResultActions r = mockMvc.perform(get("/probe/param"));
        assertProblem(r, 400);
        r.andExpect(jsonPath("$.detail").value("Brak wymaganego parametru 'x'."));
    }

    @Test
    @DisplayName("400 - brak wymaganego naglowka")
    void missingHeader() throws Exception {
        ResultActions r = mockMvc.perform(get("/probe/header"));
        assertProblem(r, 400);
        r.andExpect(jsonPath("$.detail").value("Brak wymaganego nagłówka 'X-Required'."));
    }

    @Test
    @DisplayName("400 - brak wymaganej czesci multipart")
    void missingPart() throws Exception {
        ResultActions r = mockMvc.perform(multipart("/probe/part")
                .file(new MockMultipartFile("other", "a.txt", "text/plain", "a".getBytes())));
        assertProblem(r, 400);
        r.andExpect(jsonPath("$.detail").value("Brak wymaganej części żądania 'file'."));
    }

    @Test
    @DisplayName("400 - ServletRequestBindingException (komunikat wyjatku nie wycieka)")
    void servletRequestBinding() throws Exception {
        assertProblem(mockMvc.perform(get("/probe/binding")), 400);
    }

    @Test
    @DisplayName("400 - nieczytelne cialo JSON (komunikat Jacksona nie wycieka)")
    void unreadableBody() throws Exception {
        ResultActions r = mockMvc.perform(post("/probe/json")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\": "));
        assertProblem(r, 400);
        r.andExpect(jsonPath("$.type").value("https://contactcenter.io/errors/malformed-request-body"));
    }

    @Test
    @DisplayName("400 - HandlerMethodValidationException (@Min na @RequestParam)")
    void handlerMethodValidation() throws Exception {
        ResultActions r = mockMvc.perform(get("/probe/validated").param("n", "0"));
        assertProblem(r, 400);
        // parameter name + constraint message, but never the rejected value
        r.andExpect(jsonPath("$.errors.n").isNotEmpty())
                .andExpect(jsonPath("$.errors.n").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("0"))));
    }

    @Test
    @DisplayName("400 - brak wymaganego ciasteczka")
    void missingCookie() throws Exception {
        ResultActions r = mockMvc.perform(get("/probe/cookie"));
        assertProblem(r, 400);
        r.andExpect(jsonPath("$.type").value("https://contactcenter.io/errors/missing-cookie"))
                .andExpect(jsonPath("$.detail").value("Brak wymaganego ciasteczka 'sid'."));
    }

    @Test
    @DisplayName("500 - MissingPathVariableException to blad serwera: generyczny internal-error, nie 400")
    void missingPathVariable() throws Exception {
        ResultActions r = mockMvc.perform(get("/probe/pathvar"));
        assertProblem(r, 500);
        r.andExpect(jsonPath("$.type").value("https://contactcenter.io/errors/internal-error"))
                .andExpect(jsonPath("$.detail").value(
                        "Wystąpił nieoczekiwany błąd. Spróbuj ponownie lub skontaktuj się z administratorem."))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("'id'"))));
    }

    @Test
    @DisplayName("413 - MaxUploadSizeExceededException (limit nie wycieka)")
    void maxUploadSize() throws Exception {
        ResultActions r = mockMvc.perform(get("/probe/upload-too-big"));
        assertProblem(r, 413);
        r.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("1024"))));
    }

    @Test
    @DisplayName("503 - AsyncRequestTimeoutException")
    void asyncTimeout() throws Exception {
        assertProblem(mockMvc.perform(get("/probe/async-timeout")), 503);
    }

    // ---- regresje istniejacych mapowan ----

    @Test
    @DisplayName("regresja: nieznana sciezka -> 404 (BE-161)")
    void unknownPath404() throws Exception {
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://contactcenter.io/errors/resource-not-found"))
                .andExpect(jsonPath("$.message").value("Żądana ścieżka nie istnieje."));
    }

    @Test
    @DisplayName("regresja: zla metoda -> 405 z Allow (BE-161)")
    void wrongMethod405() throws Exception {
        mockMvc.perform(post("/probe/param"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")))
                .andExpect(jsonPath("$.type").value("https://contactcenter.io/errors/method-not-allowed"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("regresja: ResponseStatusException -> jego status + message")
    void responseStatusException() throws Exception {
        mockMvc.perform(get("/probe/rse"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Brak nagrania"))
                .andExpect(jsonPath("$.type").value("https://contactcenter.io/errors/response-status"));
    }

    @Test
    @DisplayName("regresja: MethodArgumentNotValidException -> 422")
    void bodyValidation422() throws Exception {
        mockMvc.perform(post("/probe/json").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @DisplayName("regresja: MethodArgumentTypeMismatchException -> 400")
    void typeMismatch400() throws Exception {
        mockMvc.perform(get("/probe/validated").param("n", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://contactcenter.io/errors/invalid-parameter-type"));
    }

    @Test
    @DisplayName("regresja: nieobsluzony wyjatek -> 500 catch-all bez szczegolow")
    void catchAll500() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("https://contactcenter.io/errors/internal-error"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret_table"))));
    }

    @Test
    @DisplayName("regresja: lokalny handler HttpMessageNotReadableException w CampaignArchiveSettingController nadal daje 422 (nie 400)")
    void campaignArchiveLocalHandlerStillWins() throws Exception {
        mockMvc.perform(put("/api/tenants/" + TENANT_A + "/retention/campaign-archive")
                        .contentType(MediaType.APPLICATION_JSON).content("not-json"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @DisplayName("BE-200: BusinessValidationException -> 422 problem+json z jej komunikatem w detail")
    void businessValidation422() throws Exception {
        mockMvc.perform(get("/probe/business"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://contactcenter.io/errors/invalid-argument"))
                .andExpect(jsonPath("$.title").value("Nieprawidłowe dane"))
                .andExpect(jsonPath("$.detail").value("Email jest już zajęty"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("BE-200: zwykly IllegalArgumentException -> 500 internal-error bez jego komunikatu")
    void illegalArgument500WithoutMessage() throws Exception {
        mockMvc.perform(get("/probe/iae"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://contactcenter.io/errors/internal-error"))
                .andExpect(jsonPath("$.detail").value(GENERIC_DETAIL))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secretField"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("com.contactcenter"))));
    }

    @Test
    @DisplayName("BE-200: Enum.valueOf / UUID.fromString z blednym wejsciem -> bez nazw klas w odpowiedzi")
    void libraryIllegalArgumentDoesNotLeakClassNames() throws Exception {
        for (String path : new String[] {"/probe/enum-valueof", "/probe/enum-valueof-app", "/probe/uuid-fromstring"}) {
            mockMvc.perform(get(path).param("v", "not-valid"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.type").value("https://contactcenter.io/errors/internal-error"))
                    .andExpect(jsonPath("$.detail").value(GENERIC_DETAIL))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("com.contactcenter"))))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("No enum constant"))));
        }
    }
}
