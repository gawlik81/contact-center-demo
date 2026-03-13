---
name: user_preferences_prd
description: Preferencje użytkownika dotyczące formatu PRD i stylu współpracy przy planowaniu produktu
type: user
---

# Preferencje użytkownika – sesje PRD

## Profil
- Użytkownik buduje system Contact Center jako projekt wewnętrzny (SaaS)
- Decyzyjność techniczna: ustalone technologie (Angular, Java/Spring Boot, Python, PostgreSQL)
- Komunikacja: język polski

## Preferencje formatu PRD
- Użytkownik akceptuje bardzo szczegółowy dokument PRD z wieloma sekcjami
- Oczekuje tabel dla User Stories z kolumnami: ID, User Story, Priorytet MoSCoW, Kryteria akceptacji
- Oczekuje konkretnych, mierzalnych wymagań niefunkcjonalnych (liczby, percentyle)
- Preferuje ASCII diagramy dla architektury wysokiego poziomu
- Oczekuje słownika pojęć (appendix)
- Otwarte pytania muszą być wyraźnie wyodrębnione z właścicielem i terminem

## Wzorce dla projektów contact center / telco
- Kluczowe terminy: AHT, ASA, FCR, sticky agent, skill-based routing, disposition code, IVR, dialer (progressive/predictive/preview)
- Typowe pominięcia: billing/pricing model, wybór dostawcy telefonii, konkretne platformy social media, strategia voicebot (in-house vs gotowe)
- Compliance zawsze: RODO dla danych z UE, retencja nagrań, audit log
- Multi-tenancy wymaga: izolacja danych, limity per tenant, onboarding tenanta jako osobny flow
