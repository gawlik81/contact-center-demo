---
name: feedback-stale-target-classes-migrations-false-failures
description: mvn verify -pl app bez poprzedzającego `mvn clean` może dać fałszywe niepowodzenia testów EXPLAIN-plan/definicji indeksów przez zabrudzony target/classes/db/migration z innej równolegle pracującej gałęzi
metadata:
  type: feedback
---

Przy `mvn verify -pl app` bez wcześniejszego `mvn clean -pl app` dostałem 7 niepowodzeń w testach
zupełnie niezwiązanych z moją zmianą (EPIC-30 `social_message`/`email_message`: asercje tekstowe
na `EXPLAIN` i definicjach indeksów, np. oczekiwano `"ON public.social_message"`, otrzymano
`"ON ONLY public.social_message"`). Zweryfikowałem je w izolacji (tylko te 4 klasy) — dawały
IDENTYCZNE niepowodzenia, co błędnie wygląda na "pre-existing, środowiskowy problem".

**Why:** w tym repo równolegle pracują różne gałęzie/agenci w tym samym katalogu roboczym —
`target/classes/db/migration/` może zawierać nieaktualne pliki migracji z innej gałęzi/przebiegu
(Flyway/Testcontainers budują schemat z tego, co leży w `target/classes`, nie bezpośrednio z
`src/main/resources`). Plan zapytań i definicje indeksów zależą od aktualnego schematu — zabrudzony
cache dał inny (ale konsekwentny, powtarzalny) plan niż oczekiwany przez test.

**How to apply:** ZAWSZE `mvn clean -pl app` przed `mvn verify -pl app`, kiedy raport zawiera
niepowodzenia dotyczące EXPLAIN/definicji indeksów/schematu w obszarach, których nie dotykałeś —
nie zgłaszaj ich jako "pre-existing/niezwiązane" tylko na podstawie reprodukcji w izolacji (to NIE
wystarczający dowód — zabrudzony `target/classes` jest współdzielony między pojedynczymi
przebiegami w tym samym katalogu, więc "izolacja" nie czyści go). Po `mvn clean` pełny `verify`
dał 2212/2212 zielone. Zobacz też [[feedback_maven_parallel_agents_stale_classes]] (inny symptom
tego samego źródła problemu: współdzielony `target/` między równoległymi agentami) i
[[feedback_mvn_clean_race_after_subagent]] w pamięci innych agentów (orchestrator) — `mvn clean`
tuż po czyimś buildzie może race'ować, więc sprawdź brak aktywnych procesów `mvn` przed `clean`.
