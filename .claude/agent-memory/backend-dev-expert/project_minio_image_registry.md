---
name: project-minio-image-registry
description: Obrazy minio/minio i minio/mc NIE są już anonimowo pobieralne z Docker Hub (401) — testy/CI muszą używać quay.io; docker-compose.yml nadal wskazuje Docker Hub (działa tylko z lokalnego cache)
metadata:
  type: project
---

Stan z 2026-09-21: `registry-1.docker.io/v2/minio/minio` i `minio/mc` odpowiadają `401 insufficient_scope` (token bez `access`), `hub.docker.com/v2/repositories/minio/minio/tags` → „object not found"; repozytorium kontrolne `library/alpine` działa. Obraz jest publikowany na **`quay.io/minio/minio`** i `quay.io/minio/mc` (200).

- `EmailAttachmentStorageServiceMinioTest` używa `quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z`; digest indeksu `sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e` = lokalny `minio/minio:latest` (ten sam IMAGE ID `14cea493d9a3`). Na quay.io istnieją też rebuildy `RELEASE.2025-09-07T16-13-09Z.hotfix.<sha>` (kwiecień 2026) — kandydat na świadomy bump.
- `docker-compose.yml` (`minio/minio:latest`, `minio/mc:latest`) NIE został zmieniony (poza zakresem poprawek BE-125): na świeżej maszynie/`docker compose pull`/przy usuniętym lokalnym obrazie stos się nie podniesie.

**Why:** BE125-07 z code review wskazywał tylko „ruchomy tag `latest`", ale prawdziwy problem był poważniejszy — test na czystym runnerze CI (`ubuntu-latest`, brak cache'u) w ogóle by się nie uruchomił.

**How to apply:** nowe testy/kompozycje z MinIO → `quay.io/minio/...:RELEASE.…`, nie Docker Hub; przy pracy nad `docker-compose.yml`/DEPLOYMENT.md zaproponuj przepięcie na quay.io z przypiętym tagiem. `DockerImageName.parse("repo:tag@sha256:…")` w Testcontainers NIE obsługuje tagu razem z digestem — albo tag, albo digest. Powiązane: [[project-epic30-be125-message-purge]].
