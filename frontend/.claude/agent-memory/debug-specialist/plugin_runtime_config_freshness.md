---
name: plugin-runtime-config-freshness
description: Jak działa odczyt configu instalacji pluginu (PluginContext.config()) między load() i kolejnymi invocations — gdzie config jest świeży, a gdzie zamrożony
type: project
---

W pakiecie `com.contactcenter.domain.plugin.runtime` (backend Spring Boot) `PluginConfigImpl`
parsuje `installationConfigJson` RAZ w konstruktorze do niemutowalnej mapy — per-instancja jest
więc statyczny. Pytanie o "stale config" przesuwa się na to, jak często tworzona jest nowa
instancja `PluginContextImpl`:

- `PluginRuntimeManagerImpl.load()` tworzy `PluginContextImpl` tylko na czas `onActivate()` —
  ten obiekt NIE jest zachowywany w `PluginInstanceHandle` (handle ma tylko entryPoint/classLoader/
  metadane, nie context).
- `ExtensionPointPublisherImpl.buildPluginContext()` (wołane z `invokeBlocking`/`runOnWorkerThread`
  dla MANUAL_ACTION i PRE_CONTACT_CONNECT) tworzy NOWY `PluginContextImpl` PRZY KAŻDYM wywołaniu i
  odczytuje `installationConfig` świeżym zapytaniem `pluginCatalogQueryService.findInstallation(...)`
  — czyli nowy SELECT do bazy per-invocation, bez cache (`PluginCatalogQueryServiceImpl.findInstallation`
  nie ma `@Cacheable`, cały pakiet `domain.plugin` jest bez adnotacji cache).
- `PluginRegistrationServiceImpl.updateConfig()` (PATCH config) jest WYŁĄCZNIE operacją na bazie
  (`installationRepository.updateInstallationConfig`) — nie woła `pluginRuntimeManager.unload()/load()`,
  nie invaliduje żadnego cache. To jest bezpieczne właśnie dlatego, że runtime i tak czyta config
  na żywo per-invocation dla blocking extension points (MANUAL_ACTION, PRE_CONTACT_CONNECT).

**Wniosek:** dla instalacji już `enabled`, PATCH configu BEZ disable/enable DOCIERA do kolejnego
manual action / pre-contact-connect call — nie trzeba reaktywować instalacji. To było jawnie
naprawione jako "bug krytyczny" (komentarz w kodzie referuje BE-101/BE-102) — wcześniej
`buildPluginContext` zawsze zwracał `List.of()`/`null` niezależnie od rzeczywistych danych.

**Nie zweryfikowane / poza zakresem tej notatki:** ścieżka fire-and-forget (POST_CONTACT_END,
CUSTOMER_SYNC, DISPOSITION_SET) idzie przez RabbitMQ → `PluginInvocationConsumer` — nie sprawdzone,
czy tam config jest odczytywany analogicznie świeżo (prawdopodobnie tak, wzorowane na tej samej
filozofii, ale warto zweryfikować przy następnym podobnym zgłoszeniu).

**Jak debugować podobne zgłoszenia "PATCH config nie zadziałał":** problem zwykle NIE jest w
warstwie runtime/freshness (ona działa poprawnie) — sprawdzić raczej: (a) czy plugin sam cache'uje
wartości configu w polach instancji ustawionych w `onActivate()` i nie odczytuje `ctx.config()`
ponownie w kolejnych callbackach (bug w SAMYM pluginie, nie w platformie), (b) czy nazwy kluczy w
PATCH payload zgadzają się z kluczami, których plugin szuka, (c) `plugin_invocation_log` dla
faktycznego requestPayload widzianego przez plugin.
