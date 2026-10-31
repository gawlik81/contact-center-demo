---
name: project-epic32-tura48
description: EPIC-32 (UX/a11y agenta, FE-114..125) stan po turze 48 - FE-115/117 done, zakres przeniesiony do FE-116
metadata:
  type: project
---

Po turze 48 (2026-10-23): EPIC-32 FE 3/12 (FE-114, FE-115, FE-117 done). Reszta kontrastu (ramki kontrolek -> --border-control, migracja --on-danger/--on-success w ok. 20 miejscach supervisor/admin, legacy --color-text-primary, listboxy bez :focus-visible, pigulka Available w dark) przeniesiona do FE-116 (zlozonosc M/L).

**Why:** FE-115 zrobil tokeny globalne, ale komponenty z wlasnym borderem i supervisor/admin zostaly poza powierzchnia agenta.
**How to apply:** przy FE-116 sprawdzic liste (b) w tickecie; FE-117 nie ma twardej zaleznosci do FE-116/118 (tylko zalecenie). Liczniki liczyc skryptem po naglowkach `### XX-nnn` + pole `**Status:**`; BE z grepa daje 142/11 vs PROGRESS 143/10 (BE-108/109 bez naglowka) - nie ruszac.
