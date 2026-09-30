---
name: project-minio-image-registry
description: Obrazy minio/minio i minio/mc NIE są już anonimowo pobieralne ANI z Docker Hub, ANI (od ok. 2026-09-30) z quay.io — żaden sprawdzony publiczny rejestr nie serwuje ich anonimowo; BE-144 (przejście na quay.io) jest NIEAKTUALNE
metadata:
  type: project
---

**AKTUALIZACJA 2026-09-30:** `quay.io/minio/minio` (zarówno tag przypięty w BE-125, jak i `latest`) też już odpowiada 401/„no such manifest" anonimowemu zapytaniu — `quay.io/api/v1/repository/minio/minio` → HTTP 401 „Requires authentication" (wcześniej, 2026-09-21, to samo repo było publicznie odpytywalne). Sprawdzono też `ghcr.io/minio/minio` (denied) i `docker.io/bitnami/minio` (no such manifest) — **brak** sprawdzonego publicznego rejestru serwującego ten obraz anonimowo. To realnie zablokowało CI (GitHub Actions, `ubuntu-latest`, zero lokalnego cache) na `EmailAttachmentStorageServiceMinioTest` — patrz [[project-be144-minio-test-skip-afterall-pitfall]] dla obejścia (skip zamiast fail) i notatkę „Aktualizacja 2026-09-30" w `TASKS-BACKEND.md` BE-144. Wniosek dla BE-144: przejście na quay.io (jego pierwotny zakres) jest **niewystarczające** — potrzebny mirror do rejestru kontrolowanego przez zespół (GHCR namespace repo) albo uwierzytelnione pull w CI.

Stan z 2026-09-21 (już częściowo nieaktualny, patrz wyżej): `registry-1.docker.io/v2/minio/minio` i `minio/mc` odpowiadają `401 insufficient_scope` (token bez `access`), `hub.docker.com/v2/repositories/minio/minio/tags` → „object not found"; repozytorium kontrolne `library/alpine` działa. Obraz był wtedy jeszcze publikowany na **`quay.io/minio/minio`** i `quay.io/minio/mc` (200) — to już nie jest prawdą 2026-09-30.

- `EmailAttachmentStorageServiceMinioTest` używa `quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z`; digest indeksu `sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e` = lokalny `minio/minio:latest` (ten sam IMAGE ID `14cea493d9a3`). Na quay.io istnieją też rebuildy `RELEASE.2025-09-07T16-13-09Z.hotfix.<sha>` (kwiecień 2026) — kandydat na świadomy bump.
- `docker-compose.yml` (`minio/minio:latest`, `minio/mc:latest`) NIE został zmieniony (poza zakresem poprawek BE-125): na świeżej maszynie/`docker compose pull`/przy usuniętym lokalnym obrazie stos się nie podniesie.

**Why:** BE125-07 z code review wskazywał tylko „ruchomy tag `latest`", ale prawdziwy problem był poważniejszy — test na czystym runnerze CI (`ubuntu-latest`, brak cache'u) w ogóle by się nie uruchomił.

**How to apply:** nowe testy/kompozycje z MinIO → `quay.io/minio/...:RELEASE.…`, nie Docker Hub; przy pracy nad `docker-compose.yml`/DEPLOYMENT.md zaproponuj przepięcie na quay.io z przypiętym tagiem. `DockerImageName.parse("repo:tag@sha256:…")` w Testcontainers NIE obsługuje tagu razem z digestem — albo tag, albo digest. Powiązane: [[project-epic30-be125-message-purge]].
