---
name: Contact detail modal and audio player (FE-028 / FE-030)
description: ContactDetailModalComponent + AudioPlayerComponent in shared/components, FE-030 integration in CustomerDetailComponent
type: project
---

FE-028 i FE-030 zrealizowane 2026-04-08.

## Nowe pliki

- `shared/components/audio-player/audio-player.component.ts/html/scss` – standalone OnPush, input src+durationSeconds, HTML5 Audio API z własnym UI (play/pause SVG, input[type=range] seek, MM:SS czas, pobieranie przez `<a download>`), obsługa error/canplay/waiting/playing, ChangeDetectorRef.markForCheck() z event listenerami (usuwane w ngOnDestroy)
- `shared/components/contact-detail-modal/contact-detail-modal.component.ts/html/scss` – standalone OnPush, native `<dialog>`, input `contactId: string | null`, output `closed`, ngOnChanges+ngAfterViewInit → syncDialogState(), 3 sekcje: informacje ogólne / status i czas / nagranie (lazy: przycisk "Załaduj nagranie" → getRecordingUrl), skeleton loading, inline error

## Rozszerzone pliki

- `core/models/contact.model.ts` – ContactResponse rozszerzony o: queueId?, campaignId?, remoteAddress?, answeredAt?, durationSeconds?, recordingPath?; channel/status/direction ze union types; nowy interfejs RecordingUrlResponse {presignedUrl, expiresAt, fileName, durationSeconds?}
- `features/agent/models/contact.model.ts` – re-eksport rozszerzony o RecordingUrlResponse
- `features/agent/services/contact.service.ts` – dodano getContact(contactId) → GET /api/contacts/{id} i getRecordingUrl(contactId) → GET /api/contacts/{id}/recording

## FE-030 integracja w CustomerDetailComponent

- `selectedContactId = signal<string | null>(null)` dodane do klasy
- `ContactDetailModalComponent` w imports[]
- Wiersze tabeli klikalne: `(click)="selectedContactId.set(contact.id)"`, `(keydown.enter)`, `(keydown.space)`, `tabindex="0"`, styl `.contacts-table__row--clickable` z hover+focus-visible
- `<app-contact-detail-modal [contactId]="selectedContactId()" (closed)="selectedContactId.set(null)" />` na końcu template

## Wzorzec modalny (potwierdzony)

Natywny `<dialog>` z `viewChild<ElementRef<HTMLDialogElement>>('dialogEl')`, showModal() w ngAfterViewInit i ngOnChanges, ESC przez `host: { '(document:keydown.escape)': 'onEscapeKey($event)' }`, backdrop click przez `(click)="onBackdropClick($event)"` z sprawdzeniem `event.target === dialog`.

**Why:** FE-028 otwiera FE-029 (ContactsReportComponent) — wszystkie zależności spełnione.
**How to apply:** ContactDetailModalComponent dostępny jako shared import dla przyszłych tabel kontaktów (FE-029).
