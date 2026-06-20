---
name: project_be098_plugin_validation
description: BE-098 PluginValidationService (EPIC-28) — pipeline walidacji JAR-a pluginu, self-referencyjny checksum SHA-256, ASM scan, JSON Schema manifestu
metadata:
  type: project
---

BE-098 dodał pakiet `domain.plugin` w module `app` (`backend/app/src/main/java/com/contactcenter/domain/plugin/`):
encje `Plugin`/`PluginVersion` (mapowanie tabel globalnych V074, bez `tenant_id`/RLS — wzorzec
jak `domain.tenant.TenantRepository`, zwykły package-private `JpaRepository`),
`PluginManifest` (record + `UiPanel`/`ManualAction` zagnieżdżone), `ExtensionPoint` (enum 5
wartości), `PluginPermission` (walidator stringów uprawnień), `PluginManifestValidator` (JSON
Schema), `PluginBytecodeScanner` (ASM), `PluginValidationServiceImpl` (orkiestracja pipeline 6
kroków z ARCHITECTURE.md §11.4). DTO w `dto/`: `ValidationResult`, `ValidationStatus`,
`PluginVersionDto`.

**Zależności dodane do `backend/app/pom.xml`:** `com.contactcenter:contact-center-plugin-sdk`
(`${project.version}`, wymaga `mvn install -pl plugin-sdk` lub build z roota PRZED `mvn compile
-pl app` — inaczej `Could not find artifact`), `com.networknt:json-schema-validator:1.5.6`
(wybrany nad everit-org/json-schema — aktywniej rozwijany, natywna zgodność z Jackson 2.17.x
już w projekcie), `org.ow2.asm:asm:9.10.1` + `org.ow2.asm:asm-tree:9.10.1` (brak konfliktu
wersji — nic innego w projekcie nie ciągnie ASM transitively).

**Decyzja architektoniczna kluczowa — self-referencyjny checksum SHA-256 (krok 2 walidacji):**
ARCHITECTURE.md §11.4 mówi dosłownie "Compute SHA-256 of the **uploaded bytes**; compare
against manifest.checksumSha256". Zaimplementowane DOSŁOWNIE (hash całego JAR-a łącznie z
manifestem zawierającym ten checksum) jest **matematycznie niewykonalne do spełnienia** —
manifest wewnątrz JAR-a zawiera SHA-256 samego JAR-a, czyli wymaga punktu stałego funkcji
hashującej (nie istnieje w praktyce dla SHA-256). Potwierdzone empirycznie: żadna kombinacja
ZIP STORED/DEFLATED, fixed timestamp, fixed/fake CRC, in-place byte replacement nie daje
zgodności — to nie jest błąd implementacji ZIP-a, to właściwość kryptograficzna.

**Rozwiązanie zastosowane (odstępstwo od literalnego tekstu, zachowujące cel):** checksum jest
liczony z SHA-256 wszystkich wpisów ZIP-a **z wyłączeniem `META-INF/plugin-manifest.json`**
samego (`PluginValidationServiceImpl.sha256OfEntriesExcludingManifest`, wpisy sortowane
alfabetycznie dla determinizmu niezależnie od porządku `ZipFile#entries()`). Analogia: standardowy
`META-INF/MANIFEST.MF` w JAR-ach Javy też nigdy nie zawiera checksumu samego siebie; Maven
`.sha256` jest plikiem zewnętrznym; Docker image digest nie jest polem we własnym manifeście.
Cel "detects accidental corruption; NOT a substitute for signing" (ARCHITECTURE.md) jest
zachowany — manifest nie jest bardziej podatny na uszkodzenie transferowe niż inne metadane.

**Why:** bez tej zmiany żaden legalny plugin (z poprawnie wyliczonym przez dostawcę checksumem)
mógłby nigdy przejść walidacji — funkcjonalność byłaby martwa od dnia 1.

**How to apply:** BE-099 (zapis JAR-a do storage) i BE-101 (PluginContextImpl, ładowanie
ClassLoader per tenant) MUSZĄ być zgodne z tą interpretacją checksumu — jeśli dokumentacja dla
dostawców pluginów (frontend/docs SDK) opisuje krok budowy checksumu, musi też wykluczać
manifest z hashowanej treści. Jeśli przyszły ticket zmieni ten algorytm, zaktualizować też
`TestJarBuilder.sha256OfEntriesExcept` w testach (musi zostać w sync z produkcyjną logiką).

**Inne decyzje projektowe w BE-098:**
- `PluginPermission.isAllowed`: zbiór dokładnych stringów (`customer:read`, `customer:update`,
  `contact:read`, `contact:update`) + prefiks `http:egress:<host>` z dowolnym hostem (walidacja
  tylko kształtu, nie DNS) — egress allow-list per-host wymuszany w runtime przez BE-101, nie tu.
- `sdkVersion`: sprawdzany tylko major (`"1.x"`/`"1"` akceptowane, `"2.x"` odrzucone) —
  `SUPPORTED_SDK_MAJOR = "1"` w `PluginValidationServiceImpl`.
- ASM blacklist (`PluginBytecodeScanner`): `java/lang/reflect/{Method,Field,Constructor,
  AccessibleObject}#setAccessible`, prefiksy `java/lang/ProcessBuilder`, `java/nio/file/`,
  `sun/misc/`, oraz subclassing `java/lang/ClassLoader` (`superName` check). Działa na
  `ClassNode`/`MethodInsnNode` z `asm-tree`, `ClassReader.SKIP_FRAMES|SKIP_DEBUG`, BEZ
  `ClassLoader.loadClass` (statyczna inspekcja bytecode).
- `entryPointClass` musi mieć `classNode.interfaces` zawierające `PluginEntryPoint` BEZPOŚREDNIO
  (nie przez hierarchię wielopoziomową) — zgodnie z kontraktem SDK "no-arg constructor, prosta
  implementacja" z BE-097.
- `ValidationStatus` ma tylko `VALIDATED`/`REJECTED` w tej implementacji — `PENDING_REVIEW`
  (manual review przed signing) świadomie NIE jest zwracany, bo logika podpisu jest odłożona
  (OQ-28-1). Krok 5 (`verifySignatureNoOp`) jest hook na przyszłość, no-op teraz.
- Resource JSON Schema: `backend/app/src/main/resources/plugin/plugin-manifest.schema.json`
  (draft 2020-12, `JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)`).

**Testy:** `PluginValidationServiceImplTest` (14 scenariuszy) + `TestJarBuilder` (helper budujący
JAR-y w pamięci przez ASM `ClassWriter`, bez fixture'ów na dysku — generuje bytecode
poprawnych/niepoprawnych klas `PluginEntryPoint` programowo, włącznie z klasą wywołującą
`Method.setAccessible(true)` bezpośrednio przez instrukcje ASM). Brak potrzeby ładować te klasy
przez `ClassLoader` — skaner działa wyłącznie na ASM `ClassNode`, zgodnie z produkcyjnym
kontraktem "scan przed dotknięciem klasy".

**Struktura backend (przypomnienie z BE-097, wciąż aktualne):** moduł Maven `app` ma katalog
Java w `backend/app/src/main/java/...`, ALE migracje Flyway są we WSPÓLNYM katalogu
`backend/src/main/resources/db/migration/` (osobny `<resource>` w `app/pom.xml`, NIE
`app/src/main/resources/db/`). Nie pomylić tych dwóch lokalizacji przy kolejnych tickecich.
