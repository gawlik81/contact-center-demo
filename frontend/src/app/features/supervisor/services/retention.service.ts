import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import {
  EMPTY,
  Observable,
  catchError,
  combineLatest,
  map,
  shareReplay,
  switchMap,
  timer,
} from 'rxjs';
import { toObservable } from '@angular/core/rxjs-interop';
import { environment } from '../../../../environments/environment';
import { AuthService } from '../../../core/services/auth.service';
import { PagedResponse } from '../../../core/models/paged-response.model';
import {
  PurgeAcceptedResponse,
  PurgeRequestDto,
  PurgeResultDto,
  RetentionDataCategory,
  RetentionPolicyDto,
  RetentionSummaryDto,
  UpdateRetentionPolicyRequest,
} from '../models/retention.model';

/**
 * Interwał odpytywania dla {@link RetentionService.pendingDeletionCategoryCount$} (FE-108) —
 * CELOWO ten sam interwał co `AdminMetricsService.POLL_INTERVAL_MS` (FE-007), żeby nie
 * wprowadzać drugiej konwencji pollingu w projekcie.
 */
const BADGE_POLL_INTERVAL_MS = 30_000;

/**
 * Warstwa danych panelu „Ustawienia > Retencja danych" (EPIC-29, FE-103). Czysta warstwa
 * danych — bez logiki UI, komponenty stron dochodzą w FE-104+.
 *
 * Woła `RetentionController` (backend, BE-118): wszystkich 6 endpointów wymaga `{tenantId}`
 * W ŚCIEŻCE URL — kontroler jawnie weryfikuje, że `{tenantId}` ze ścieżki zgadza się z tenantem
 * z JWT wywołującego (403 przy niezgodności; serwisy domenowe same tego NIE sprawdzają, bo
 * `RetentionEvaluationJob` legalnie woła je dla wszystkich tenantów po kolei).
 *
 * **Wybór tenantId (A, udokumentowany):** `tenantId` jest czytany WEWNĘTRZNIE, z
 * `AuthService.currentTenantId()`, w każdej metodzie — NIE jako parametr wołającego. To inny
 * wzorzec niż `TwilioConfigService` (tam `tenantId` jest przekazywany przez wołającego), ale ten
 * wzorzec jest tam jawnie oznaczony jako „legacy". Podejście A daje prostszy kontrakt dla
 * przyszłych komponentów FE-104+ (nie muszą znać/przekazywać tenantId przy każdym wywołaniu) i
 * jest zgodne z sygnaturami zaproponowanymi w tickecie FE-103. Panel jest dostępny wyłącznie dla
 * roli ADMIN w kontekście zalogowanego tenanta (`roleGuard` to gwarantuje), więc
 * `currentTenantId()` w praktyce zawsze zwraca wartość — mimo to każda metoda broni się przed
 * `null` (rzuca czytelny błąd zamiast zakładać, że guard zawsze zadziała poprawnie).
 */
@Injectable({ providedIn: 'root' })
export class RetentionService {
  private readonly http = inject(HttpClient);
  private readonly authService = inject(AuthService);

  private buildBaseUrl(tenantId: string): string {
    return `${environment.apiUrl}/tenants/${tenantId}/retention`;
  }

  /**
   * Zwraca bieżący tenantId z JWT lub rzuca czytelny błąd, gdy kontekst tenanta jest
   * niedostępny (nie powinno się zdarzyć — `roleGuard` gwarantuje kontekst ADMIN + tenant przed
   * dotarciem do panelu retencji — ale bronimy się defensywnie zamiast to zakładać).
   */
  private requireTenantId(): string {
    const tenantId = this.authService.currentTenantId();
    if (!tenantId) {
      throw new Error(
        'RetentionService: brak tenantId w kontekście zalogowanego użytkownika — panel retencji wymaga roli ADMIN w kontekście tenanta.',
      );
    }
    return tenantId;
  }

  /**
   * Reaktywny strumień: liczba kategorii retencji (0–4) z `eligibleRowCount > 0` wg
   * najnowszego `GET .../retention/summary`. Zasila globalny badge „dane do usunięcia" w
   * `SidenavComponent` (FE-108) — jeden serwis = jedna domena danych, ten sam serwis co
   * dashboard Sekcji 2 (FE-105), więc logika sumowania mieszka tu, a nie w osobnym serwisie.
   *
   * Bramkowanie ROLĄ I tenantem jest reaktywne (nie jednorazowy `getUserRole()`) — mirrors
   * dokładnie `AdminMetricsService._poll$` (FE-007): `toObservable(currentRole)` + `switchMap`
   * + `timer(0, BADGE_POLL_INTERVAL_MS)` + `shareReplay({ refCount: true })`. Retencja jest
   * funkcją ADMIN TENANTA (nie SUPER_ADMIN globalnego), więc bramkujemy DODATKOWO obecnością
   * `currentTenantId()` — `getSummary()` (przez `requireTenantId()`) rzuca SYNCHRONICZNIE gdy
   * tenantId jest `null`, więc każdy tick timera sprawdza tenantId ponownie tuż przed
   * wywołaniem `getSummary()`, zamiast polegać wyłącznie na bramce z chwili startu timera
   * (unika wyścigu, gdyby tenantId zniknął między tickami).
   *
   * UWAGA (znane ograniczenie, poza zakresem FE-108): dopóki BE-116 nie jest ukończone,
   * `RECORDINGS` ma zawsze `computed === false` i `eligibleRowCount === 0` w odpowiedzi
   * backendu — sumujemy `eligibleRowCount` dosłownie zgodnie z kryterium akceptacji FE-108,
   * bez próby odróżniania „jeszcze nie policzone" od „policzono, zero do usunięcia" tutaj.
   */
  readonly pendingDeletionCategoryCount$: Observable<number> = combineLatest([
    toObservable(this.authService.currentRole),
    toObservable(this.authService.currentTenantId),
  ]).pipe(
    switchMap(([role, tenantId]) => {
      if (role !== 'ADMIN' || !tenantId) {
        // Nie-ADMIN lub brak kontekstu tenanta – nic nie emitujemy. Ewentualny działający
        // timer z poprzedniej sesji ADMIN jest automatycznie zatrzymywany przez switchMap.
        return EMPTY;
      }
      return timer(0, BADGE_POLL_INTERVAL_MS).pipe(
        switchMap(() => {
          if (!this.authService.currentTenantId()) {
            // Kontekst tenanta zniknął między tickami (np. wylogowanie w toku) – pomiń ten
            // cykl zamiast wołać getSummary(), która rzuciłaby synchronicznie.
            return EMPTY;
          }
          return this.getSummary().pipe(
            map((summary) => summary.filter((s) => s.eligibleRowCount > 0).length),
            catchError(() => EMPTY),
          );
        }),
      );
    }),
    shareReplay({ bufferSize: 1, refCount: true }),
  );

  /** GET .../retention/policies — lista polityk retencji tenanta (maks. 4, jedna per kategoria). */
  listPolicies(): Observable<RetentionPolicyDto[]> {
    const tenantId = this.requireTenantId();
    return this.http.get<RetentionPolicyDto[]>(`${this.buildBaseUrl(tenantId)}/policies`);
  }

  /**
   * PUT .../retention/policies/{category} — upsert polityki retencji dla jednej kategorii.
   * Backend wymaga, aby `dataCategory` w body zgadzała się z `{category}` ze ścieżki (422 przy
   * niezgodności) — wysyłamy ją jawnie w request body.
   */
  updatePolicy(
    category: RetentionDataCategory,
    retentionMonths: number,
    autoPurgeEnabled: boolean,
  ): Observable<RetentionPolicyDto> {
    const tenantId = this.requireTenantId();
    const request: UpdateRetentionPolicyRequest = {
      dataCategory: category,
      retentionMonths,
      autoPurgeEnabled,
    };
    return this.http.put<RetentionPolicyDto>(
      `${this.buildBaseUrl(tenantId)}/policies/${category}`,
      request,
    );
  }

  /**
   * GET .../retention/summary — dashboard „ile danych kwalifikuje się do usunięcia", ZAWSZE
   * dokładnie 4 wpisy (cache `tenant_retention_pending_summary`, wypełniany przez
   * `RetentionEvaluationJob`, BE-112). Zobacz `RetentionSummaryDto.computed` w modelu — odróżnij
   * „jeszcze nie policzone" od „policzono, zero do usunięcia".
   */
  getSummary(): Observable<RetentionSummaryDto[]> {
    const tenantId = this.requireTenantId();
    return this.http.get<RetentionSummaryDto[]>(`${this.buildBaseUrl(tenantId)}/summary`);
  }

  /**
   * POST .../retention/recompute — ręczne przeliczenie WSZYSTKICH 4 kategorii na żądanie, bez
   * auto-purge. Zwraca ten sam kształt co {@link getSummary} (świeże wartości, zapisane od razu do
   * cache) — pozwala odświeżyć dashboard Sekcji 2 bez czekania na nocny `RetentionEvaluationJob`
   * (BE-112), np. zaraz po zmianie polityki retencji. `RECORDINGS` pozostaje `computed === false`
   * także po przeliczeniu — poza zakresem tego endpointu (patrz {@link RetentionSummaryDto}).
   */
  recomputeSummary(): Observable<RetentionSummaryDto[]> {
    const tenantId = this.requireTenantId();
    return this.http.post<RetentionSummaryDto[]>(`${this.buildBaseUrl(tenantId)}/recompute`, {});
  }

  /**
   * POST .../retention/purge — inicjuje asynchroniczne usuwanie danych dla kategorii. Zwraca
   * `purgeId` NATYCHMIAST (202 Accepted), bez czekania na zakończenie — status operacji odpytuj
   * przez {@link getPurgeStatus}.
   */
  triggerPurge(category: RetentionDataCategory): Observable<PurgeAcceptedResponse> {
    const tenantId = this.requireTenantId();
    const request: PurgeRequestDto = { dataCategory: category };
    return this.http.post<PurgeAcceptedResponse>(`${this.buildBaseUrl(tenantId)}/purge`, request);
  }

  /** GET .../retention/purge/{purgeId} — status trwającej lub zakończonej operacji purge. */
  getPurgeStatus(purgeId: string): Observable<PurgeResultDto> {
    const tenantId = this.requireTenantId();
    return this.http.get<PurgeResultDto>(`${this.buildBaseUrl(tenantId)}/purge/${purgeId}`);
  }

  /**
   * GET .../retention/history — paginowana historia operacji purge tenanta. Backend sortuje
   * ZAWSZE malejąco po `startedAt` i ignoruje sort z query — celowo nie eksponujemy tu parametru
   * `sort`.
   */
  getHistory(page: number, size: number): Observable<PagedResponse<PurgeResultDto>> {
    const tenantId = this.requireTenantId();
    const params = new HttpParams().set('page', page.toString()).set('size', size.toString());
    return this.http.get<PagedResponse<PurgeResultDto>>(`${this.buildBaseUrl(tenantId)}/history`, {
      params,
    });
  }
}
