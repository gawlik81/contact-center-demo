/**
 * Modele warstwy danych panelu „Ustawienia > Retencja danych" (EPIC-29, FE-103).
 *
 * Kształty 1:1 z DTO backendu (Java records w
 * `backend/.../domain/retention/dto/`, BE-118), zweryfikowane bezpośrednio w kodzie —
 * Jackson serializuje pola record domyślnie 1:1, więc nazwy pól TS odpowiadają nazwom pól Java.
 */

/** Zgodne z CHECK constraint `tenant_retention_policy.data_category` (V082, DB-046). */
export type RetentionDataCategory =
  | 'CONTACT_INTERACTIONS'
  | 'RECORDINGS'
  | 'TRANSCRIPTS'
  | 'CAMPAIGN_DATA';

/** Zgodne z CHECK constraint `retention_purge_log.trigger_type` (V084, DB-048). */
export type PurgeTriggerType = 'MANUAL' | 'AUTO';

/**
 * `status` operacji purge jest Stringiem po stronie Javy (nie enumem), ale wartości to
 * dokładnie te trzy stałe (`RetentionPurgeLog.STATUS_*`).
 */
export type PurgeStatus = 'RUNNING' | 'COMPLETED' | 'FAILED';

/** Odpowiada `RetentionPolicyDto` (Java record). */
export interface RetentionPolicyDto {
  policyId: string;
  tenantId: string;
  dataCategory: RetentionDataCategory;
  retentionMonths: number;
  autoPurgeEnabled: boolean;
  /** UUID użytkownika, który jako ostatni zmienił politykę; null po seedowaniu. */
  updatedBy: string | null;
  createdAt: string;
  updatedAt: string;
}

/**
 * Odpowiada `RetentionSummaryDto` (Java record). Endpoint `GET .../summary` zwraca ZAWSZE
 * dokładnie 4 wpisy (jeden per {@link RetentionDataCategory}).
 *
 * UWAGA — pole `computed` jest jawnym sygnałem stanu, ODRÓŻNIJ oba przypadki:
 * - `computed === false` → `RetentionEvaluationJob` (BE-112) jeszcze NIE policzył tej kategorii
 *   (np. dziś zawsze dla `RECORDINGS` — job jej celowo nie liczy, `RecordingRetentionJob` to
 *   zakres BE-116, jeszcze nieukończony); `eligibleRowCount` jest wtedy `0`, ale to NIE znaczy
 *   „nic do usunięcia".
 * - `computed === true` + `eligibleRowCount === 0` → policzono, faktycznie zero do usunięcia.
 */
export interface RetentionSummaryDto {
  dataCategory: RetentionDataCategory;
  eligibleRowCount: number;
  oldestEligiblePeriod: string | null;
  newestEligiblePeriod: string | null;
  computedAt: string | null;
  computed: boolean;
}

/**
 * Odpowiada `PurgeResultDto` (Java record). Backend celowo NIE ma osobnych
 * `PurgeStatusDto`/`PurgeHistoryEntryDto` — ten sam kształt jest zwracany zarówno przez
 * `GET .../purge/{purgeId}` jak i każdy element `GET .../history`. Robimy to samo w TS zamiast
 * tworzyć dwa zbędne duplikaty (ticket FE-103 sugerował je jako różne typy — to jest odstępstwo,
 * udokumentowane zgodnie z rzeczywistym kontraktem backendu).
 */
export interface PurgeResultDto {
  purgeId: string;
  tenantId: string;
  dataCategory: RetentionDataCategory;
  triggerType: PurgeTriggerType;
  /** UUID użytkownika, który wywołał purge ręcznie; null dla triggerType `AUTO`. */
  triggeredBy: string | null;
  cutoffDate: string;
  /** Suma usuniętych wierszy; null dopóki `status === 'RUNNING'`. */
  rowsDeleted: number | null;
  status: PurgeStatus;
  startedAt: string;
  /** null dopóki `status === 'RUNNING'`. */
  completedAt: string | null;
  /** Komunikat błędu przy `status === 'FAILED'`; null przy sukcesie/w trakcie. */
  errorMessage: string | null;
}

/** Alias dla czytelności w miejscach wołających `getHistory` — ten sam kształt co {@link PurgeResultDto}. */
export type PurgeHistoryEntryDto = PurgeResultDto;

/**
 * Request body dla `PUT .../policies/{category}` (odpowiada `UpdateRetentionPolicyRequest`).
 *
 * UWAGA — `dataCategory` jest WYMAGANE w body, mimo że kategoria jest już w ścieżce URL: backend
 * waliduje zgodność obu wartości i zwraca 422 przy niezgodności (path jest źródłem prawdy).
 */
export interface UpdateRetentionPolicyRequest {
  dataCategory: RetentionDataCategory;
  retentionMonths: number;
  autoPurgeEnabled: boolean;
}

/** Request body dla `POST .../purge` (odpowiada `PurgeRequestDto`). */
export interface PurgeRequestDto {
  dataCategory: RetentionDataCategory;
}

/** Odpowiedź `POST .../purge` (202 Accepted) — operacja jest asynchroniczna, wynik dociera później. */
export interface PurgeAcceptedResponse {
  purgeId: string;
}
