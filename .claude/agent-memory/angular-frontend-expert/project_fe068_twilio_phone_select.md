---
name: Twilio phone number select component (FE-068)
description: Reużywalny ControlValueAccessor select dla numerów Twilio – integracja w TwilioConfigComponent i CampaignFormComponent
type: project
---

Stworzono `TwilioPhoneNumberSelectComponent` (selector `app-twilio-phone-number-select`) w
`features/supervisor/components/twilio-phone-number-select/`.

**Kluczowe decyzje:**
- Implementuje `ControlValueAccessor` z `NG_VALUE_ACCESSOR` – działa jako pole reaktywnego formularza
- Pięć stanów UI: `loading | error-502 | no-config | empty | ready` jako `signal<LoadState>`
- Błąd 404 → `no-config`, pozostałe błędy → `error-502` z przyciskiem retry (re-wywołanie `fetchPhoneNumbers()`)
- Input `allowNull: boolean` dodaje pierwszą opcję z `nullLabel` i wartością `null` (dla CampaignForm)
- `selectId` generowane jako unikalny string `twilio-phone-select-XXXXX` – label parent binduje `[for]="ref.selectId"`
- Wartość formularza: string `phoneNumber` E.164 (NIE sid)
- `options` obliczane przez `computed()` z `phoneNumbers()` signal

**TwilioConfigService** rozszerzony o:
- `TwilioPhoneNumberDto` interfejs (`sid`, `phoneNumber`, `friendlyName`)
- `getPhoneNumbers(): Observable<TwilioPhoneNumberDto[]>` – GET `/api/supervisor/twilio-config/phone-numbers`

**Integracja TwilioConfigComponent (FE-066):**
- Pole `phoneNumber` zmieniło typ z `''` na `null as string | null` (no E.164 validator – select gwarantuje poprawność)
- Usunięto getter `phoneNumberError` i walidator pattern
- `patchFormFromResponse` zachowany bez zmian – `config.phoneNumber ?? ''` nadal działa (ControlValueAccessor przyjmuje string)

**Integracja CampaignFormComponent (FE-067):**
- Usunięto import `TwilioConfigService` i signal `defaultPhoneNumber` (nie potrzeba pre-fetch domyślnego numeru)
- Pole `callerId` bez walidatora pattern (select zwraca walidowane numery)
- `allowNull=true` + `nullLabel` = "— Domyślny numer tenanta —" jako pierwsza opcja
- Komponent renderowany tylko gdy `campaignType() === 'OUTBOUND_VOICE'` (lazy)
- Usunięto getter `callerIdError`

**i18n:** klucze dodane do pl/en/de/uk pod `supervisor.twilioPhoneSelect.*` i zaktualizowane `supervisor.campaignForm.callerId.*`

**Why:** ujednolicenie UX przy wyborze numerów Twilio, eliminacja ręcznego wpisywania E.164.
**How to apply:** przy kolejnych formularzach wymagających wyboru numeru Twilio – użyj `app-twilio-phone-number-select` z `formControlName`.
