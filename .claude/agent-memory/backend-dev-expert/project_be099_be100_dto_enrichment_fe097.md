---
name: project_be099_be100_dto_enrichment_fe097
description: Wzbogacenie PluginVersionDto/TenantPluginInstallationDto po fakcie (2026-06-22) z powodu niezgodności kontraktu BE-098/099/100/106 z frontendowym FE-097
metadata:
  type: project
---

EPIC-28 (system pluginów per tenant): wykryto niezgodność kontraktu między specyfikacją
frontendową FE-097 (`TASKS-FRONTEND.md`) a DTO faktycznie zwracanymi przez już zaimplementowany
i zakomitowany backend (BE-098/099/100/106). Użytkownik zdecydował rozszerzyć backend zamiast
zmieniać frontend, żeby lista pluginów nie wymagała dodatkowych round-tripów po
displayName/manualActions/uiPanels.

**Zmiany (2026-06-22):**
- `PluginVersionDto`: dodano `displayName`, `vendor` (z `Plugin`).
- `TenantPluginInstallationDto`: dodano `pluginKey`, `displayName`, `version` (semver
  `PluginVersion`, NIE pomylić z polem `pluginVersionId` które jest UUID), `manualActions:
  List<ManualActionDto>`, `uiPanels: List<UiPanelDto>`.
- Nowe publiczne rekordy w `domain/plugin/dto/`: `ManualActionDto(actionId, label, mountPoint)`,
  `UiPanelDto(panelId, mountPoint, url)` — odpowiedniki package-private `PluginManifest.ManualAction`/
  `UiPanel` (pakiet `domain.plugin`, niewidoczne z `domain.plugin.dto`). `UiPanelDto` świadomie
  BEZ pola `sandbox` — model TS `UiPanelDef` z FE-097 go nie ma.
- **Bug znaleziony przy tej okazji:** `PluginStorageServiceImpl#manifestToMap` (BE-099) NIGDY nie
  zapisywał `uiPanels`/`manualActions` do `manifestJson` (JSONB) — te pola były parsowane do
  `PluginManifest` przy uploadzie, ale gubione przy budowie mapy do zapisu. Naprawione w tej samej
  zmianie — inaczej wzbogacenie `TenantPluginInstallationDto` zawsze zwracałoby puste listy.
- `PluginRegistrationServiceImpl#mapToDto` teraz przyjmuje `(TenantPluginInstallation,
  PluginVersion)` — dociąga `PluginVersion`+`Plugin` (EAGER `@ManyToOne`) do zbudowania
  wzbogaconych pól. `listInstallations` używa `pluginVersionRepository.findAllById(...)` (batch,
  jedno zapytanie) zamiast N+1; `install`/`getInstallation`/`rollback` już miały/dociągają
  `PluginVersion` pojedynczo (i tak potrzebny do innej logiki, np. permissions).

**Pułapka Jackson odkryta tutaj:** `ObjectMapper.convertValue(map, record.class)` domyślnie
**rzuca** `UnrecognizedPropertyException` dla pól obecnych w mapie źródłowej, ale nieobecnych w
rekordzie docelowym (`FAIL_ON_UNKNOWN_PROPERTIES=true` jest defaultem) — nie "ignoruje po cichu",
jak można by się intuicyjnie spodziewać. Manifest niesie `uiPanels[].sandbox`, którego `UiPanelDto`
nie ma → trzeba jawnie `ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
false)` na instancji używanej do tej konwersji. Zobacz [[feedback_jackson_convertvalue_unknown_properties]].

Zobacz też [[project_be100_plugin_registration]], [[project_be106_plugin_admin_controller]],
[[project_be098_plugin_validation]].
