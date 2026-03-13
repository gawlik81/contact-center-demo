---
name: contact_center_project
description: Kluczowe decyzje i kontekst projektu systemu Contact Center SaaS
type: project
---

# Projekt: System Contact Center SaaS

## Status dokumentu
PRD wygenerowany 2026-03-12. Plik: D:\CloudeAI\contact-center-demo\PRD_ContactCenter.md

## Kluczowe decyzje architektoniczne
- Model: SaaS multi-tenant z izolacją logiczną (nie fizyczną) między tenantami
- Frontend: Angular (TypeScript) – SPA
- Backend: Java + Spring Boot (logika biznesowa), Python (AI/automatyzacja)
- Baza danych: PostgreSQL z kolumną tenant_id we wszystkich tabelach
- Telefonia: zewnętrzny dostawca VoIP przez wzorzec adaptera (dostawca niezdefiniowany)
- Data Warehouse: do integracji z narzędziami BI (Power BI, Tableau, Metabase)

## Zakres MVP (Faza 1)
- Kanały: telefon (VoIP inbound+outbound), email, social media
- Dialer: progressive (predictive i preview w Fazie 2)
- IVR + voicebot + chatbot
- Routing: skill-based + sticky agent + simple queue
- Profil klienta + historia kontaktów (pełny CRM w Fazie 3)
- Nagrywanie rozmów (min. 90 dni retencji, konfigurowalnie)
- 3 persony: Administrator (globalny/techniczny), Supervisor (per tenant/biznesowy), Agent
- REST API z JWT (time-limited token)
- RODO/GDPR compliance
- Max 100 agentów jednocześnie per tenant

## Kanały planowane w późniejszych fazach
- RCS (SMS, MMS, VMS) – Faza 3
- Predictive/preview dialer – Faza 2
- Whisper/barge-in dla supervisora – Faza 2

## Otwarte decyzje krytyczne
- OQ-01: Wybór dostawcy telefonii VoIP (POC: Twilio vs Telnyx)
- OQ-02: Konkretne platformy social media w MVP
- OQ-04: Voicebot/chatbot in-house vs Dialogflow/Rasa
- OQ-08: Moduł billing – własny czy zewnętrzny
