import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnDestroy,
  OnInit,
  afterNextRender,
  computed,
  inject,
  Injector,
  NgZone,
  input,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { NgClass } from '@angular/common';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { catchError, EMPTY, filter, tap } from 'rxjs';
import { ContactService } from '../../services/contact.service';
import { DispositionSaveService } from '../../services/disposition-save.service';
import { ContactTabStore } from '../../services/contact-tab.store';
import { ContactDraftStore } from '../../services/contact-draft.store';
import { DISPOSITION_CODES } from '../../models/disposition.model';
import { AiSummaryPanelComponent } from '../../../../shared/components/ai-summary-panel/ai-summary-panel.component';
import { CustomDispositionService } from '../../../../features/dispositions/services/custom-disposition.service';
import { WebSocketService } from '../../../../core/services/websocket.service';
import { RecordingReadyPayload } from '../../models/ws-event.model';

interface AvailableDispositionForPanel {
  code: string;
  label: string;
  tone: string;
}

@Component({
  selector: 'app-disposition-panel',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, NgClass, TranslocoModule, AiSummaryPanelComponent],
  templateUrl: './disposition-panel.component.html',
  styleUrl: './disposition-panel.component.scss',
})
export class DispositionPanelComponent implements OnInit, OnDestroy {
  /** ID zakładki/kontaktu – wymagane aby wiedzieć co zapisać */
  readonly contactId = input.required<string>();
  /** Key of the ACW drafts (see `acwDraftKey`); defaults to `contactId`. */
  readonly draftKey = input<string>('');
  /** ID of the PHONE tab this form belongs to (focus requests are addressed to it). */
  readonly tabId = input<string>('');
  /** Epoch ms when ACW started; the timer is derived from it so it survives remounts. */
  readonly wrappingSince = input<number | null>(null);
  /** Nazwa klienta do wyświetlenia w nagłówku */
  readonly customerName = input<string>('');
  /** Notatka z rozmowy wpisana przez agenta – pre-fills pole notatki */
  readonly prefillNotes = input<string>('');

  private readonly contactService = inject(ContactService);
  private readonly saver = inject(DispositionSaveService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly transloco = inject(TranslocoService);
  private readonly customDispositionService = inject(CustomDispositionService);
  private readonly wsService = inject(WebSocketService);
  private readonly tabStore = inject(ContactTabStore);
  private readonly drafts = inject(ContactDraftStore);
  private readonly key = computed(() => this.draftKey() || this.contactId());
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private readonly ngZone = inject(NgZone);

  protected readonly availableDispositions = signal<AvailableDispositionForPanel[]>([]);
  protected readonly dispositionsLoading = signal(false);
  protected readonly dispositionsError = signal<string | null>(null);

  protected readonly selectedCode = signal<string>('');
  protected readonly notes = signal<string>('');
  protected readonly isSaving = computed(() => this.saver.isSaving(this.contactId()));
  /** Validation is shown only after the agent tried to save. */
  protected readonly submitAttempted = signal(false);
  protected readonly showCodeError = computed(
    () => this.submitAttempted() && this.selectedCode().length === 0,
  );

  /** ACW timer: a visual clock only, deliberately outside any live region. */
  private readonly now = signal(Date.now());
  private readonly mountedAt = Date.now();
  private tickInterval: ReturnType<typeof setInterval> | null = null;
  private pendingFocus = false;

  protected readonly formattedAcwTime = computed(() => {
    const since = this.wrappingSince() ?? this.mountedAt;
    const total = Math.max(0, Math.floor((this.now() - since) / 1000));
    const m = Math.floor(total / 60);
    const s = total % 60;
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  });

  readonly hasRecording = signal(false);

  ngOnInit(): void {
    // A remount (tab switch) restores what the agent had entered; the note typed during the call
    // only pre-fills a form that has no draft yet.
    const draft = this.drafts.dispositionDraft(this.key());
    if (draft) {
      this.selectedCode.set(draft.code);
      this.notes.set(draft.notes);
    } else if (this.prefillNotes()) {
      this.notes.set(this.prefillNotes());
    }
    // Outside the zone: the signal marks this view, no application-wide tick every second.
    this.ngZone.runOutsideAngular(() => {
      this.tickInterval = setInterval(() => this.now.set(Date.now()), 1000);
    });

    this.contactService
      .getContact(this.contactId())
      .pipe(
        tap((c) => this.hasRecording.set(!!c.recordingUrl)),
        catchError(() => EMPTY),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe();

    this.wsService.events$
      .pipe(
        filter((e) => e.eventType === 'RECORDING_READY'),
        filter((e) => (e.payload as RecordingReadyPayload).contactId === this.contactId()),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => this.hasRecording.set(true));

    const cached = this.drafts.availableDispositions(this.contactId());
    if (cached) {
      this.applyOptions(cached);
    } else {
      this.loadDispositions();
    }

    // Focus only on an explicit request (ACW tab activated, banner button, call ended on this
    // tab). A plain mount (tab switch, history navigation) never moves it.
    if (this.tabId() !== '' && this.tabStore.consumeAcwFocus(this.tabId())) {
      this.focusFirstField();
    }
  }

  private loadDispositions(): void {
    this.dispositionsLoading.set(true);
    this.customDispositionService
      .getAvailableDispositions(this.contactId())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (dispositions) => {
          const options = dispositions.map((d) => ({
            code: d.dispositionCode,
            label: d.label,
            tone: d.tone,
          }));
          this.drafts.setAvailableDispositions(this.contactId(), options);
          this.applyOptions(options);
        },
        error: (err) => {
          console.warn(
            '[DispositionPanel] Failed to load dispositions from API, using fallback',
            err,
          );
          // Not cached: the next mount tries the API again.
          this.applyOptions(
            DISPOSITION_CODES.map((d) => ({
              code: d.code,
              label: this.transloco.translate(d.labelKey),
              tone: 'neutral',
            })),
          );
          this.dispositionsError.set(this.transloco.translate('agent.disposition.fallbackNotice'));
        },
      });
  }

  private applyOptions(options: AvailableDispositionForPanel[]): void {
    this.availableDispositions.set(options);
    this.dispositionsLoading.set(false);
    // A drafted code that this list does not offer (fallback list vs API list) must not be sent.
    if (this.selectedCode() && !options.some((o) => o.code === this.selectedCode())) {
      this.selectedCode.set('');
      this.persistDraft();
    }
    this.flushPendingFocus();
  }

  /** Roving tabindex: the chosen code, else the first one, is the single tab stop. */
  protected isTabStop(code: string, index: number): boolean {
    const selected = this.selectedCode();
    return selected ? selected === code : index === 0;
  }

  /** Radio-group keyboard model: arrows move AND select (wrapping), like native radios. */
  protected onRadioKeydown(event: KeyboardEvent, index: number): void {
    const list = this.availableDispositions();
    let next: number | null = null;
    if (event.key === 'ArrowRight' || event.key === 'ArrowDown') next = (index + 1) % list.length;
    else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
      next = (index - 1 + list.length) % list.length;
    } else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = list.length - 1;
    if (next === null) return;
    event.preventDefault();
    this.selectCode(list[next].code);
    afterNextRender(
      () => this.host.nativeElement.querySelectorAll<HTMLElement>('.disp-card')[next!]?.focus(),
      { injector: this.injector },
    );
  }

  ngOnDestroy(): void {
    if (this.tickInterval !== null) clearInterval(this.tickInterval);
  }

  /** Focuses the selected code (or the first one); waits for the list when it is still loading. */
  private focusFirstField(): void {
    this.pendingFocus = true;
    if (!this.dispositionsLoading()) this.flushPendingFocus();
  }

  private flushPendingFocus(): void {
    if (!this.pendingFocus) return;
    this.pendingFocus = false;
    afterNextRender(
      () => {
        const cards = Array.from(
          this.host.nativeElement.querySelectorAll<HTMLElement>('.disp-card'),
        );
        const target =
          cards.find((c) => c.getAttribute('aria-checked') === 'true') ??
          cards[0] ??
          this.host.nativeElement.querySelector<HTMLElement>('textarea');
        target?.focus();
      },
      { injector: this.injector },
    );
  }

  protected toneClass(tone: string): string {
    const map: Record<string, string> = {
      positive: 'tone-positive',
      negative: 'tone-negative',
      warning: 'tone-warning',
      neutral: 'tone-neutral',
    };
    return map[tone] ?? 'tone-neutral';
  }

  protected selectCode(code: string): void {
    this.selectedCode.set(code);
    this.persistDraft();
  }

  protected onNotesChange(value: string): void {
    this.notes.set(value);
    this.persistDraft();
  }

  protected onAiSummaryCopy(summary: string): void {
    const current = this.notes().trim();
    this.notes.set(current ? current + '\n\n' + summary : summary);
    this.persistDraft();
  }

  /** Keeps the form across tab switches; the store is cleared when the tab closes. */
  private persistDraft(): void {
    this.drafts.setDispositionDraft(this.key(), {
      code: this.selectedCode(),
      notes: this.notes(),
    });
  }

  protected save(): void {
    if (this.isSaving()) return;
    if (this.selectedCode().length === 0) {
      // Validation starts here, not on render; the error is announced once and the agent lands
      // on the field that needs fixing.
      this.submitAttempted.set(true);
      this.focusFirstField();
      return;
    }
    // The request outlives this component (the agent may switch tabs meanwhile).
    this.saver.save({
      tabId: this.tabId(),
      contactId: this.contactId(),
      code: this.selectedCode(),
      notes: this.notes(),
    });
  }
}
