package com.contactcenter.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-200: straznik kontraktu. {@code IllegalArgumentException} oznacza blad programistyczny/techniczny
 * i jest mapowany na HTTP 500 (bez komunikatu). Walidacje biznesowe dostepne przez REST musza rzucac
 * {@code BusinessValidationException} (422). Test skanuje zrodla (ArchUnit nie jest zaleznoscia).
 *
 * <p>Zakres: caly pakiet {@code com.contactcenter.api} oraz klasy {@code *ServiceImpl}/{@code *Controller}
 * w {@code com.contactcenter.domain}. Wyjatki ponizej to swiadome, nieosiagalne z wejscia uzytkownika miejsca.
 */
@DisplayName("Kontrakt BE-200: brak new IllegalArgumentException w warstwie API/serwisow")
class IllegalArgumentContractTest {

    private static final Path MAIN = Path.of("src/main/java/com/contactcenter");
    private static final String NEEDLE = "new IllegalArgumentException(";

    /** Sciezka wzgledem com/contactcenter -> uzasadnienie. */
    private static final Set<String> ALLOW_LIST = Set.of(
            // niezmiennik wewnetrzny: konwersja wartosci z natywnego zapytania (Cannot convert to Instant)
            "domain/disposition/DispositionSetServiceImpl.java",
            // konfiguracja startowa / niezmiennik: klucz hex szyfrowania, zly ciphertext
            "domain/email/EmailEncryptionServiceImpl.java",
            // niezmiennik: pusty klucz S3 do usuniecia (wywolanie wewnetrzne, nie REST)
            "domain/email/EmailAttachmentStorageServiceImpl.java",
            // serializacja config do JSON - blad techniczny
            "domain/plugin/PluginRegistrationServiceImpl.java",
            // guard statusu (kontroler sprawdza status wczesniej) - blad programistyczny
            "domain/plugin/PluginStorageServiceImpl.java");

    @Test
    @DisplayName("nowe 'new IllegalArgumentException(' w api.. i domain..*ServiceImpl/*Controller tylko z allow-listy")
    void noUnlistedIllegalArgumentExceptionInApiAndServices() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Files.walk(MAIN)) {
            offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(p -> MAIN.relativize(p).toString().replace('\\', '/'))
                    .filter(IllegalArgumentContractTest::inScope)
                    .filter(rel -> !ALLOW_LIST.contains(rel))
                    .filter(rel -> contains(MAIN.resolve(rel)))
                    .sorted()
                    .toList();
        }
        assertThat(offenders)
                .as("Uzyj BusinessValidationException dla walidacji biznesowej (422) albo dodaj do ALLOW_LIST "
                        + "z uzasadnieniem (IAE => HTTP 500)")
                .isEmpty();
    }

    @Test
    @DisplayName("allow-lista nie zawiera martwych wpisow")
    void allowListEntriesStillNeeded() {
        for (String rel : ALLOW_LIST) {
            assertThat(contains(MAIN.resolve(rel)))
                    .as("Wpis allow-listy %s nie zawiera juz IAE - usun go", rel)
                    .isTrue();
        }
    }

    private static boolean inScope(String rel) {
        if (rel.startsWith("api/")) {
            return true;
        }
        return rel.startsWith("domain/") && (rel.endsWith("ServiceImpl.java") || rel.endsWith("Controller.java"));
    }

    private static boolean contains(Path file) {
        try {
            return Files.readString(file).contains(NEEDLE);
        } catch (IOException e) {
            throw new IllegalStateException("Nie mozna odczytac " + file, e);
        }
    }
}
