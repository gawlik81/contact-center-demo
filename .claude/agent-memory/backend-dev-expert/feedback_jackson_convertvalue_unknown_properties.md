---
name: feedback_jackson_convertvalue_unknown_properties
description: ObjectMapper.convertValue(Map, record) rzuca wyjątek dla nieznanych pól, nie ignoruje ich domyślnie
metadata:
  type: feedback
---

`ObjectMapper.convertValue(sourceMap, TargetRecord.class)` z domyślną konfiguracją Jacksona
**rzuca** `IllegalArgumentException` (zawijający `UnrecognizedPropertyException`) gdy mapa
źródłowa zawiera pole nieobecne w docelowym rekordzie/klasie — `FAIL_ON_UNKNOWN_PROPERTIES` jest
`true` domyślnie w Jacksonie.

**Why:** Błędnie założono (i błędnie opisano w Javadoc przed weryfikacją), że Jackson "ignoruje
nieznane pola przy convertValue bez dodatkowej konfiguracji" — odkryte przez test integracyjny,
który realnie wywołał `mvn test`, nie przez czytanie dokumentacji. Manifest pluginu (EPIC-28)
niesie `uiPanels[].sandbox`, którego docelowy publiczny DTO (`UiPanelDto`) nie ma (świadomie, bo
frontendowy model go nie potrzebuje) — konwersja rzucała `IllegalArgumentException` w runtime.

**How to apply:** Każdy raz, gdy mapujesz `Map<String,Object>`/JSON surowy na rekord przez
`ObjectMapper.convertValue` i docelowy typ ma **mniej** pól niż źródło (np. DTO "okrojony" celowo
względem pełnej struktury domenowej), skonfiguruj dedykowaną instancję `ObjectMapper` z
`.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)`. Nie zakładaj domyślnego
zachowania — zweryfikuj testem, nie tylko czytając kod/dokumentację z pamięci. Zobacz
[[project_be099_be100_dto_enrichment_fe097]] dla konkretnego przypadku.
