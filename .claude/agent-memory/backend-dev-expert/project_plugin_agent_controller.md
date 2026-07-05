---
name: project_plugin_agent_controller
description: Nowy endpoint GET /api/agent/plugins (PluginAgentController) — widok agenta na pluginy enabled=true, dodany dla FE-100 known-gap
metadata:
  type: project
---

EPIC-28 (system pluginów per tenant), kontynuacja serii FE-097/FE-098/FE-099/BE-099/BE-100 —
ten sam wzorzec: ticket FE-100 (panel boczny + toolbar agenta) przewidywał jako known-gap brak
endpointu, z którego agent (rola AGENT) mógłby pobrać listę zainstalowanych pluginów, bo jedyny
istniejący `GET /api/supervisor/plugins` (`PluginAdminController`) ma
`@PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")` → agent dostałby 403. Zdecydowano
rozszerzyć backend (4. raz z rzędu w tej sesji), nie iść na mock/TODO we froncie.

**Dodano (2026-06-23):**
- Nowy plik `backend/app/src/main/java/com/contactcenter/api/plugin/PluginAgentController.java`
  — świadomie OSOBNY kontroler od `PluginAdminController` (inny `@RequestMapping`, inna rola,
  inna semantyka — admin widzi wszystko w tym disabled, agent tylko enabled).
- Kontrakt: `GET /api/agent/plugins`, `@PreAuthorize("hasAnyRole('AGENT', 'SUPERVISOR', 'ADMIN')")`,
  zwraca `List<TenantPluginInstallationDto>` (ten sam DTO co supervisor endpoint, bez nowych pól).
- Implementacja: reużywa `PluginRegistrationService.listInstallations(tenantId)` (BE-100, już
  istniał, zwraca WSZYSTKIE instalacje tenanta) i filtruje w kontrolerze
  `.filter(TenantPluginInstallationDto::enabled)` — zero zmian w warstwie serwisu/repo.
- Test: `PluginAgentControllerTest` — wzorzec identyczny jak `PluginAdminControllerTest`
  (jednostkowy, bez `@WebMvcTest`, bo projekt nie ma takiej infrastruktury dla `api.plugin`;
  `TenantContext` mockowany statycznie). Kryterium: lista z mieszanką enabled/disabled →
  kontroler zwraca tylko enabled.
- `mvn verify -pl app` zielony: 1353 testów, 0 failures po dodaniu.
- Uwaga dla FE: filtr backendu jest TYLKO po `enabled` — `healthStatus !== 'DISABLED_BY_ADMIN'`
  (drugi warunek z kryteriów akceptacji FE-100) trzeba nadal sprawdzić po stronie frontendu na
  zwróconej liście, backend go nie odfiltrowuje.
- Notatka z dokładnym kontraktem dodana w `TASKS-FRONTEND.md` pod sekcją FE-100.

Zobacz też [[project_be099_be100_dto_enrichment_fe097]], [[project_be100_plugin_registration]],
[[project_be106_plugin_admin_controller]].
