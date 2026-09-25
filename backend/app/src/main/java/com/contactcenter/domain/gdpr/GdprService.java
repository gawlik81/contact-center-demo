package com.contactcenter.domain.gdpr;

import com.contactcenter.api.customer.dto.AnonymizePreviewResponse;
import com.contactcenter.domain.exception.ConflictException;
import jakarta.persistence.EntityNotFoundException;

import java.util.UUID;

/**
 * Serwis realizujący operacje RODO (BE-031, rozszerzony BE-129).
 *
 * <p>Implementuje dwa prawa podmiotów danych:
 * <ul>
 *   <li>Art. 15/20 – Prawo dostępu / przenoszenia danych: {@link #exportCustomerData(UUID)}
 *       woła funkcję SQL {@code export_customer_data} (DB-061) i buduje archiwum ZIP z
 *       kompletem zbiorów danych klienta oraz manifestem kluczy S3 (presigned URL-e — pliki
 *       audio/załączniki NIE są wklejane do ZIP-a).</li>
 *   <li>Art. 17 – Prawo do bycia zapomnianym: {@link #anonymizeCustomer(UUID)} woła funkcję SQL
 *       {@code anonymize_customer} (DB-062) w jednej transakcji, a po commit sprząta best-effort
 *       obiekty S3 wskazane przez funkcję (nagrania, EML, załączniki e-mail/social).</li>
 * </ul>
 *
 * <p><strong>Zbiór podmiotu (D9 = A):</strong> obie operacje reużywają tę samą funkcję pomocniczą
 * PostgreSQL {@code fn_customer_subject_ids} (DB-061) — dopasowanie kluczem obcym ({@code link})
 * ORAZ znormalizowanym telefonem/e-mailem klienta ({@code identifier}), więc zbiór jest
 * konstrukcyjnie spójny między podglądem/eksportem a rzeczywistą anonimizacją.
 * {@link #previewAnonymizeCustomer(UUID)} pozwala zobaczyć ten zbiór (liczniki per tabela +
 * {@code matched_by}) PRZED nieodwracalną anonimizacją.
 *
 * <p><strong>Rekordy „w toku" (BE-129):</strong> jeśli klient ma powiązany
 * {@code campaign_contact} w statusie {@code DIALING} lub {@code scheduled_callback} w statusie
 * {@code PROCESSING} (połączenie faktycznie trwa w chwili wywołania), rzeczywista anonimizacja
 * (nie podgląd) kończy się {@link ConflictException} (HTTP 409) — świadoma decyzja: prostsze i
 * bezpieczniejsze niż próba dokończenia anonimizacji po zakończeniu połączenia (osobny epik).
 *
 * <p>Audyt: funkcja SQL {@code anonymize_customer} zapisuje wpis {@code CUSTOMER_ANONYMIZED}
 * atomowo z anonimizacją (Java NIE publikuje własnego wpisu dla tej operacji — jedno źródło
 * audytu na operację). Eksport publikuje {@code GDPR_EXPORT} z Javy (funkcja SQL po DB-061 nie
 * pisze już audytu).
 */
public interface GdprService {

    /**
     * Eksportuje wszystkie dane klienta do archiwum ZIP (RODO Art. 15/20).
     *
     * <p>Archiwum zawiera (bez ucięcia, komplet zbioru podmiotu D9 = A):
     * <ul>
     *   <li>{@code customer.json}, {@code contacts.json}, {@code scheduled_callbacks.json},
     *       {@code campaign_records.json}, {@code email_messages.json},
     *       {@code social_messages.json}, {@code transcriptions.json}, {@code ai_summaries.json}
     *       – pełne zbiory danych klienta z {@code export_customer_data} (DB-061)</li>
     *   <li>{@code manifest.json} – metadane eksportu (liczniki {@code matched_by}) oraz lista
     *       obiektów S3 (nagrania, EML, załączniki) z presigned URL-ami; pliki audio/załączniki
     *       NIE są wklejane do ZIP-a</li>
     * </ul>
     *
     * @param customerId UUID klienta
     * @return bajty archiwum ZIP
     * @throws EntityNotFoundException gdy klient nie istnieje w tenancie lub jest zanonimizowany
     * @throws GdprServiceImpl.GdprException gdy nie uda się zbudować archiwum ZIP lub sparsować
     *         odpowiedzi funkcji SQL
     */
    byte[] exportCustomerData(UUID customerId);

    /**
     * Anonimizuje dane osobowe klienta zgodnie z RODO Art. 17 (prawo do bycia zapomnianym).
     *
     * <p>Sekwencja: jedna transakcja (istnienie klienta → guard rekordów w toku → funkcja SQL
     * {@code anonymize_customer}, DB-062) → PO COMMIT best-effort usunięcie obiektów S3 wskazanych
     * przez funkcję (kolejność DB → S3, DESIGN R8: błąd bazy nie może zostawić usuniętych obiektów
     * S3). Niepowodzenia usuwania S3 są logowane (nie cofają anonimizacji PII) — pozwalają dokończyć
     * ręcznie.
     *
     * <p>Identyczny efekt w bazie co {@code DELETE /api/customers/{id}}
     * ({@code CustomerController} przekierowuje na tę metodę, BE-129).
     *
     * @param customerId UUID klienta do anonimizacji
     * @throws EntityNotFoundException gdy klient nie istnieje w tenancie (dosanityzowanie klienta
     *         już zanonimizowanego wcześniej ścieżką Javy — {@code is_deleted = TRUE} — NIE rzuca:
     *         funkcja SQL kończy anonimizację reszty danych)
     * @throws ConflictException gdy klient ma powiązany rekord „w toku" (DIALING/PROCESSING)
     */
    void anonymizeCustomer(UUID customerId);

    /**
     * Podgląd anonimizacji klienta (RODO Art. 17) — bez żadnego efektu ubocznego.
     *
     * <p>Woła {@code anonymize_customer(…, p_dry_run = TRUE)} (DB-062): baza i S3 pozostają
     * nietknięte, brak wpisu audytowego. Zwraca liczniki per tabela oraz rozmiar zbioru podmiotu
     * ({@code matched_by_link}/{@code matched_by_identifier}), żeby operator mógł ocenić zakres
     * przed potwierdzeniem nieodwracalnej operacji (FE-112).
     *
     * @param customerId UUID klienta
     * @return liczniki podglądu anonimizacji
     * @throws EntityNotFoundException gdy klient nie istnieje w tenancie
     */
    AnonymizePreviewResponse previewAnonymizeCustomer(UUID customerId);
}
