# ClickHouse Data Warehouse – migracje (`dw/migrations/`)

Migracje DW **nie są Flyway** (to nie PostgreSQL) – wersjonowanie ręczne (`V001`, `V002`, ...).
**Nigdy nie edytuj zastosowanej migracji** – dodawaj kolejną. Migracje od `V003` są idempotentne.

## Jak migracje są stosowane

Jedyny mechanizm: kontener `clickhouse-init` w `docker-compose.yml` (jednorazowy, `exited` po
zakończeniu; `docker compose up -d` **nie** uruchamia go ponownie dla istniejącego kontenera).

- **Świeża baza** (sentinel `contact_center_dw.queue_dim` – ostatni obiekt V001, niekasowany przez V003 – jednoznacznie nie istnieje, `EXISTS TABLE` = 0): V001 → V002 → V003.
- **Istniejąca baza**: tylko V003+. V001 używa `CREATE OR REPLACE TABLE` i **czyści dane** –
  nie wolno go ponownie uruchamiać na bazie z danymi. Entrypoint jest fail-closed: błąd zapytania `EXISTS TABLE`
  (timeout, auth, restart ClickHouse) lub odpowiedź inna niż `0`/`1` kończy init kodem 1 – V001 uruchamia się wyłącznie
  na jawne `0`. (`EXISTS TABLE` na nieistniejącej bazie zwraca 0, nie rzuca błędu.)

Zastosowanie V003 (BE-215) na działającej instancji (np. local-demo / prod):

```bash
# z katalogu głównego repo; compose z flagami środowiska (patrz CLAUDE.md)
docker compose --env-file .env.local-demo -f docker-compose.yml -f docker-compose.local-demo.yml \
  run --rm clickhouse-init
# albo wprost:
docker exec -i cc-clickhouse clickhouse-client --multiquery < dw/migrations/V003__dedup_safe_agent_kpi.sql
```

V003 nie niszczy żadnej tabeli z faktami (tylko `DROP VIEW`/`CREATE OR REPLACE VIEW`) – można ją
bezpiecznie powtarzać.

## Model odczytu (od V003, BE-215)

`contacts_dw` i `campaigns_dw` to `ReplacingMergeTree(updated_at)`: ETL (po BE-213) celowo wstawia ten sam
wiersz wielokrotnie (ponowne czytanie ogona remisów kursora CDC, retry batcha), a nowa wersja kontaktu
(zmiana statusu/czasu trwania) to kolejny wiersz o większym `updated_at`. Duplikaty znikają dopiero przy
merge w tle, więc **zapytania nie mogą liczyć surowych wierszy**.

| Obiekt | Rola |
|--------|------|
| `v_contacts_current` | jedna, najnowsza wersja każdego kontaktu (`argMax(..., updated_at)` po `(tenant_id, contact_id)`; kolumny Nullable przez `tuple(...)`, bo `argMax` pomija NULL) |
| `v_agent_kpi_daily` | KPI agenta/dzień/kanał liczone z `v_contacts_current` (te same kolumny co w V001) |
| `v_campaign_conversion` | statystyki kampanii z deduplikacją `campaigns_dw` po `(campaign_id, record_id)` |

Zasada dla nowych raportów: czytaj z widoków `v_*` albo sam użyj `argMax(..., updated_at)` + `GROUP BY` po kluczu
biznesowym. Nie używaj surowego `count()`/`sum()` na `contacts_dw`. Dlaczego nie `FINAL`: deduplikuje tylko w
obrębie partycji (`toYYYYMM(started_at)`), więc nie scali wersji kontaktu, której `started_at` zmienił miesiąc.

Materialized View `mv_agent_performance_from_contacts` (V002) została usunięta w V003 – MV liczy każdy blok
INSERT osobno i nie da się jej uodpornić na powtórne wstawienia ani na nowe wersje wiersza.
`agent_performance_dw` nie jest już zasilana ani czytana.

Koszt: agregacja przy odczycie skanuje `contacts_dw` (GROUP BY po kluczu). Pomaga tylko filtr na `tenant_id`
(prefiks `ORDER BY`, a w `v_campaign_conversion` także klucz `GROUP BY`). Filtr po dacie **nie przycina partycji**:
`started_at` w widoku pochodzi z `argMax`, więc warunek na nim jest stosowany dopiero po agregacji. Dla dużego
wolumenu zmierz koszt (R2); w razie potrzeby strategia (c) – okresowe przeliczenie cache'u z `v_contacts_current`
(poza zakresem BE-215).

### Założenie: jeden sekwencyjny writer

`argMax(..., updated_at)` zakłada, że `updated_at` rośnie zgodnie z kolejnością zapisu. `ClickHouseDwWriter`
ustawia `Instant.now()` w momencie INSERT, więc przy kilku równoległych instancjach ETL (lub rozjazdzie zegarów)
starsza wersja kontaktu mogłaby wygrać z nowszą. Od BE-172 cykle ETL (`run*Sync`) są pod blokadą ShedLock, a ręczny
trigger `POST /api/admin/etl/trigger` bierze tę samą blokadę (409 `ETL_SYNC_IN_PROGRESS`, gdy cykl trwa), więc w
normalnej pracy jest jeden writer na tabelę.

**Dług (jawny):** blokada ma `lockAtMostFor` (domyślnie 10 min). Jeśli cykl potrwa dłużej lub instancja się zawiesi i
blokada wygaśnie, drugi węzeł może zapisać nowszą wersję wiersza, zanim pierwszy dopisze starszą - starsza z
późniejszym `updated_at = now()` wygra `argMax`. Test `EtlSyncShedLockIntegrationTest` dowodzi braku duplikatów na
PostgreSQL (`ON CONFLICT`), nie semantyki `argMax` w ClickHouse. Możliwe rozwiązania (osobny ticket): `updated_at` z
kolumny źródłowej zamiast `Instant.now()` albo `KeepAliveLockProvider` dla ETL.

## Korekta zawyżonych danych historycznych (BE-215)

Zawyżone były wyłącznie **agregaty** `agent_performance_dw` (MV V002). Fakty w `contacts_dw` są poprawne
(duplikaty to wersje tego samego klucza) – po V003 widoki liczą z nich prawidłowo, więc **nie trzeba
przeliczać historii**; wystarczy wyeliminować zawyżoną tabelę.

1. Zastosuj V003 (sekcja wyżej). Od tej chwili `v_agent_kpi_daily` zwraca poprawne wartości.
2. (Opcjonalnie) porównaj stare i nowe wartości:
   ```sql
   SELECT (SELECT sum(total_contacts) FROM contact_center_dw.agent_performance_dw) AS stare_zawyzone,
          (SELECT sum(total_contacts) FROM contact_center_dw.v_agent_kpi_daily)    AS poprawne;
   ```
3. Po zweryfikowaniu raportów wyłącz martwą, zawyżoną tabelę odwracalnie (zmiana nazwy), ręcznie:
   ```sql
   RENAME TABLE contact_center_dw.agent_performance_dw TO contact_center_dw.agent_performance_dw_legacy;
   ```
   `DROP TABLE contact_center_dw.agent_performance_dw_legacy` dopiero po okresie karencji (np. 2–4 tygodnie bez
   zgłoszeń). Przed RENAME upewnij się, że żaden zewnętrzny odczyt (BI, skrypty) nie czyta bezpośrednio tabeli
   `agent_performance_dw` – w kodzie aplikacji nikt jej nie czyta.
4. (Opcjonalnie) wymuś fizyczną deduplikację: `OPTIMIZE TABLE contact_center_dw.contacts_dw FINAL;`
   (oszczędza miejsce; nie zmienia wyników widoków).

## Testy

`ClickHouseDwDedupIntegrationTest` (Testcontainers, obraz `clickhouse/clickhouse-server:24.3`) stosuje
V001→V002→V003 i sprawdza: ten sam wiersz 3× = 1 jednostka, nowa wersja (zmiana statusu) nie podwaja liczby,
dedup w `v_campaign_conversion`, wersję przeniesioną do innej partycji i wersję z `NULL` (argMax pomija NULL – kolumny
Nullable są w widoku opakowane w `tuple(...)`). Wymaga Dockera (jak pozostałe testy Testcontainers – bez niego test pada).
