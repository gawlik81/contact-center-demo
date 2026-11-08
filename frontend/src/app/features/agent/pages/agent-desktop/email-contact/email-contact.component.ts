import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import {
  ChangeDetectionStrategy,
  Injector,
  afterNextRender,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  ViewChild,
  SecurityContext,
  computed,
  effect,
  untracked,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { DomSanitizer } from '@angular/platform-browser';
import {
  Observable,
  catchError,
  debounceTime,
  filter,
  finalize,
  forkJoin,
  map,
  of,
  startWith,
  switchMap,
} from 'rxjs';

import {
  EmailService,
  EmailMessage,
  EmailTemplate,
  PendingAttachment,
  SendReplyRequest,
} from '../../../services/email.service';
import { PagedResponse } from '../../../../../core/models/paged-response.model';
import { WebSocketService } from '../../../../../core/services/websocket.service';
import { ContactMessageReceivedPayload, WsEvent } from '../../../models/ws-event.model';
import { AsyncContactAcceptService } from '../../../services/async-contact-accept.service';
import { ContactConflictService, isConflict } from '../../../services/contact-conflict.service';
import { ContactService } from '../../../services/contact.service';
import { ContactDraftStore } from '../../../services/contact-draft.store';
import { EmailReplyService } from '../../../services/email-reply.service';
import { EmailThreadMessageComponent } from './email-thread-message/email-thread-message.component';
import { AiSummaryPanelComponent } from '../../../../../shared/components/ai-summary-panel/ai-summary-panel.component';

const THREAD_PAGE_SIZE = 20;

/** Contiguous run of thread pages starting at page `from`. */
interface ThreadTail {
  from: number;
  responses: PagedResponse<EmailMessage>[];
  /** The loaded range cannot be extended contiguously: replace the shown list with this tail. */
  reset?: boolean;
}

/** At most this many missing pages are read in a follow-up; a longer gap reloads the tail. */
const MAX_CATCH_UP_PAGES = 2;

@Component({
  selector: 'cc-email-contact',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslocoModule,
    ReactiveFormsModule,
    EmailThreadMessageComponent,
    AiSummaryPanelComponent,
  ],
  templateUrl: './email-contact.component.html',
  styleUrl: './email-contact.component.scss',
})
export class EmailContactComponent implements OnInit {
  contactId = input.required<string>();
  replySent = output<boolean>();

  private editorRef?: ElementRef<HTMLDivElement>;

  /**
   * The editor lives inside an @if (appears after the message loads), so restore the draft
   * body the moment the element is created instead of relying on ngOnInit timing.
   */
  @ViewChild('editor')
  private set editorElement(ref: ElementRef<HTMLDivElement> | undefined) {
    const isNew = ref !== undefined && ref !== this.editorRef;
    this.editorRef = ref;
    if (isNew && ref && this.replyHtml() && ref.nativeElement.innerHTML !== this.replyHtml()) {
      ref.nativeElement.innerHTML = this.replyHtml();
    }
  }
  @ViewChild('fileInput') private fileInputRef?: ElementRef<HTMLInputElement>;

  private readonly emailService = inject(EmailService);
  private readonly contactService = inject(ContactService);
  private readonly asyncAccept = inject(AsyncContactAcceptService);
  private readonly conflict = inject(ContactConflictService);
  private readonly ws = inject(WebSocketService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly transloco = inject(TranslocoService);
  private readonly drafts = inject(ContactDraftStore);
  private readonly emailReply = inject(EmailReplyService);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** Set once the reply was sent / returned to queue; stops draft write-back. */
  private draftClosed = false;
  private readonly subjectEdited = signal(false);

  protected readonly loading = signal(true);
  protected readonly loadingMore = signal(false);
  /** True while a send for this contact is in flight, also after the card was remounted. */
  protected readonly sending = computed(() => this.drafts.isSending(this.contactId()));
  protected readonly error = signal<string | null>(null);
  protected readonly threadCollapsed = signal(false);

  protected readonly rootMessage = signal<EmailMessage | null>(null);
  protected readonly thread = signal<EmailMessage[]>([]);
  /** Lowest / highest thread page loaded; the loaded range is contiguous and ends at the newest. */
  private readonly oldestPage = signal(0);
  private readonly newestPage = signal(0);
  protected readonly hasMore = computed(() => this.oldestPage() > 0);

  protected readonly templates = signal<EmailTemplate[]>([]);
  protected readonly selectedTemplate = signal<EmailTemplate | null>(null);
  protected readonly templateVariables = signal<Record<string, string>>({});
  protected readonly showVariableForm = signal(false);

  protected readonly replyHtml = signal<string>('');
  protected readonly replySubject = signal<string>('');

  // ===== Attachment upload state =====
  protected readonly pendingAttachments = signal<PendingAttachment[]>([]);
  protected readonly uploading = signal(false);
  protected readonly uploadError = signal<string | null>(null);

  protected readonly templateSearchControl = new FormControl<string>('');
  protected readonly filteredTemplates = signal<EmailTemplate[]>([]);

  protected readonly hasTemplateVariables = computed(() => {
    const tpl = this.selectedTemplate();
    return tpl !== null && tpl.variables.length > 0;
  });

  protected readonly canSend = computed(() => {
    const html = this.replyHtml();
    return html.trim().length > 0 && !this.sending();
  });

  constructor() {
    // A send that failed while this card was unmounted is reported once when it is shown again.
    effect(() => {
      const contactId = this.contactId();
      if (!this.drafts.hasSendFailed(contactId)) return;
      untracked(() => {
        this.error.set(this.transloco.translate('agent.emailContact.errorSend'));
        this.drafts.clearSendFailed(contactId);
      });
    });

    // Write every draft change back to the store so it survives the card being unmounted.
    effect(() => {
      const draft = {
        bodyHtml: this.replyHtml(),
        subject: this.replySubject(),
        subjectEdited: this.subjectEdited(),
        template: this.selectedTemplate(),
        templateVariables: this.templateVariables(),
        showVariableForm: this.showVariableForm(),
        attachments: this.pendingAttachments(),
      };
      if (this.draftClosed || !this.restored) return;
      this.drafts.setEmailDraft(this.contactId(), draft);
    });
  }

  private restored = false;

  private restoreDraft(): void {
    const draft = this.drafts.emailDraft(this.contactId());
    if (draft) {
      this.replyHtml.set(this.sanitizeHtml(draft.bodyHtml));
      this.replySubject.set(draft.subject);
      this.subjectEdited.set(draft.subjectEdited);
      this.selectedTemplate.set(draft.template);
      this.templateVariables.set(draft.templateVariables);
      this.showVariableForm.set(draft.showVariableForm);
      this.pendingAttachments.set(draft.attachments);
      if (draft.template) {
        this.templateSearchControl.setValue(draft.template.name, { emitEvent: false });
      }
    }
    this.restored = true;
  }

  ngOnInit(): void {
    this.destroyRef.onDestroy(() => {
      this.destroyed = true;
      this.stopPinning();
    });
    this.restoreDraft();
    this.loadMessage();
    this.loadTemplates();

    // Tell the backend the agent opened this contact (ASSIGNED -> ACTIVE, stops re-queueing).
    // Safety net, not a duplicate: normally the tab-open path has accepted already and this is a
    // no-op (guard); it matters for a tab opened by a path that did not.
    this.asyncAccept.accept(this.contactId());
    this.subscribeToWsEvents();

    this.templateSearchControl.valueChanges
      .pipe(startWith(''), debounceTime(150), takeUntilDestroyed(this.destroyRef))
      .subscribe((value) => {
        const search = value ?? '';
        const all = this.templates();
        this.filteredTemplates.set(
          search ? all.filter((t) => t.name.toLowerCase().includes(search.toLowerCase())) : all,
        );
      });
  }

  private loadMessage(): void {
    this.loading.set(true);
    this.error.set(null);

    this.emailService
      .getMessage(this.contactId())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (msg) => {
          this.rootMessage.set(msg);
          if (!this.subjectEdited()) {
            this.replySubject.set(
              msg.subject.startsWith('Re:') ? msg.subject : `Re: ${msg.subject}`,
            );
          }
          this.loadThread(msg.threadRootMessageId ?? msg.messageIdHeader ?? msg.id);
        },
        error: () => {
          this.error.set(this.transloco.translate('agent.emailContact.errorLoad'));
          this.loading.set(false);
        },
      });
  }

  private loadThread(rootId: string): void {
    // The thread API is chronological (oldest first), so the agent must open on the LAST page.
    // Page 0 doubles as the probe (a short thread is a single request); a longer one needs the
    // last page, which is derived from `totalPages` of that answer.
    this.emailService
      .getThread(rootId, 0, THREAD_PAGE_SIZE)
      .pipe(
        switchMap((first) =>
          first.totalPages <= 1
            ? of<ThreadTail>({ from: 0, responses: [first] })
            : this.readTail(rootId, first.totalPages - 1).pipe(
                switchMap((tail) => this.fillToFullPage(rootId, first, tail)),
              ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (tail) => {
          const root = this.rootMessage();
          const merged = this.mergeMessages([], tail.responses);
          this.thread.set(merged.length > 0 ? merged : root ? [root] : []);
          this.oldestPage.set(tail.from);
          this.newestPage.set(tail.from + tail.responses.length - 1);
          this.loading.set(false);
          this.scrollToNewestMessage();
          if (this.refreshPending) this.requestRefresh();
        },
        error: () => {
          // Keep the opened message visible: the agent can still read and answer it.
          const root = this.rootMessage();
          this.thread.set(root ? [root] : []);
          this.error.set(this.transloco.translate('agent.emailContact.errorThread'));
          this.loading.set(false);
        },
      });
  }

  /**
   * First load only (never for "older"): pin the top of the newest message to the top of the list
   * and keep it pinned while heights settle (each iframe grows after its content loads, however
   * late: a background tab can delay that by many seconds) or the list's surroundings change.
   * It ends at the first user interaction with the list (wheel/touch/key/mouse), on "older" or on
   * destroy. While active, messages added by a refresh are followed too. Sets the list's own
   * scrollTop (instant, no ancestor scrolling, no animation); a list that fits stays as is.
   */
  private scrollToNewestMessage(): void {
    this.stopPinning();
    afterNextRender(
      () => {
        const list = this.host.nativeElement.querySelector<HTMLElement>('.email-thread__messages');
        if (!list || this.destroyed) return;
        let active = true;
        const observer =
          typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(() => pin());
        const pin = (): void => {
          if (!active) return;
          const items = list.querySelectorAll<HTMLElement>('cc-email-thread-message');
          const last = items[items.length - 1];
          if (!last || list.scrollHeight <= list.clientHeight) return;
          const top =
            last.getBoundingClientRect().top - list.getBoundingClientRect().top + list.scrollTop;
          list.scrollTop = top;
        };
        /** Observes messages not seen yet (a no-op for known ones), then pins. */
        const sync = (): void => {
          list.querySelectorAll('cc-email-thread-message').forEach((el) => observer?.observe(el));
          pin();
        };
        observer?.observe(list); // clientHeight changes caused by the surroundings
        const userEvents = ['wheel', 'touchstart', 'keydown', 'mousedown'] as const;
        const stop = (): void => {
          active = false;
          observer?.disconnect();
          userEvents.forEach((e) => list.removeEventListener(e, stop));
          if (this.stopPin === stop) {
            this.stopPin = null;
            this.syncPin = null;
          }
        };
        userEvents.forEach((e) => list.addEventListener(e, stop, { passive: true }));
        this.stopPin = stop;
        this.syncPin = sync;
        sync();
      },
      { injector: this.injector },
    );
  }

  /** Ends the pin-to-newest behaviour (user interaction, "older", timeout, destroy). */
  private stopPinning(): void {
    this.stopPin?.();
  }

  /**
   * A short newest page (e.g. 1 message of 21) is a poor opening view: when an earlier page
   * exists, it is read too so the agent starts on at least one full page of the newest messages.
   * Page 0 is already in hand from the first request and is not fetched twice.
   */
  private fillToFullPage(
    rootId: string,
    pageZero: PagedResponse<EmailMessage>,
    tail: ThreadTail,
  ): Observable<ThreadTail> {
    const newest = tail.responses[tail.responses.length - 1];
    const prev = tail.from - 1;
    if (newest.content.length >= THREAD_PAGE_SIZE || prev < 0) return of(tail);
    const prev$ =
      prev === 0 ? of(pageZero) : this.emailService.getThread(rootId, prev, THREAD_PAGE_SIZE);
    return prev$.pipe(
      map((res): ThreadTail => ({ from: prev, responses: [res, ...tail.responses] })),
    );
  }

  /**
   * Reads `page` and verifies it against the total reported by that same answer:
   * - the thread shrank below `page` (or is empty, `totalPages` 0): the real last page is read
   *   and flagged `reset` (shown pages may hold removed messages);
   * - it grew by up to MAX_CATCH_UP_PAGES pages: the missing pages are read, once, contiguously;
   * - it grew by more (or the caller has no valid range, e.g. after a failed first load): the
   *   newest MAX_CATCH_UP_PAGES pages are read and flagged `reset`, so the fan-out stays bounded.
   * A message arriving at a page boundary is thus picked up by the follow-up read, not lost.
   */
  private readTail(rootId: string, page: number): Observable<ThreadTail> {
    const read = (p: number) => this.emailService.getThread(rootId, p, THREAD_PAGE_SIZE);
    return read(page).pipe(
      switchMap((res): Observable<ThreadTail> => {
        const last = Math.max(0, res.totalPages - 1);
        if (last === page) return of({ from: page, responses: [res] });
        if (last < page)
          return read(last).pipe(map((r) => ({ from: last, responses: [r], reset: true })));
        if (last - page <= MAX_CATCH_UP_PAGES) {
          const missing = Array.from({ length: last - page }, (_, i) => read(page + 1 + i));
          return forkJoin(missing).pipe(map((more) => ({ from: page, responses: [res, ...more] })));
        }
        return forkJoin([read(last - 1), read(last)]).pipe(
          map((tail) => ({ from: last - 1, responses: tail, reset: true })),
        );
      }),
    );
  }

  /** Applies a refresh result: merge into the shown range, or replace it after a `reset`. */
  private applyTail(tail: ThreadTail): void {
    const newest = tail.from + tail.responses.length - 1;
    if (tail.reset) {
      this.thread.set(this.mergeMessages([], tail.responses));
      this.oldestPage.set(tail.from);
    } else {
      this.thread.update((current) => this.mergeMessages(current, tail.responses));
    }
    this.newestPage.set(newest);
    // Still pinned (the agent has not scrolled): follow the messages that just arrived.
    if (this.syncPin) afterNextRender(() => this.syncPin?.(), { injector: this.injector });
  }

  /** Merges pages into the shown list by `id`: known messages are kept, new ones are appended. */
  private mergeMessages(
    current: EmailMessage[],
    responses: PagedResponse<EmailMessage>[],
  ): EmailMessage[] {
    const known = new Set(current.map((m) => m.id));
    const fresh: EmailMessage[] = [];
    for (const res of responses) {
      for (const m of res.content) {
        if (known.has(m.id)) continue;
        known.add(m.id);
        fresh.push(m);
      }
    }
    return fresh.length > 0 ? [...current, ...fresh] : current;
  }

  /**
   * CONTACT_MESSAGE_RECEIVED carries no content: the newest loaded page is re-read (one request;
   * a second only when the thread grew a page meanwhile) and merged by `id`. The loaded range
   * always ends at the newest page, so no continuity check is needed. An event during the
   * initial load or another refresh is not dropped: it sets `refreshPending` and one refresh
   * runs afterwards.
   */
  private subscribeToWsEvents(): void {
    this.ws.events$
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        filter((e: WsEvent) => e.eventType === 'CONTACT_MESSAGE_RECEIVED'),
      )
      .subscribe((e) => {
        const payload = e.payload as ContactMessageReceivedPayload;
        if (payload.contactId === this.contactId() && payload.channel === 'EMAIL') {
          this.requestRefresh();
        }
      });
  }

  private destroyed = false;
  private stopPin: (() => void) | null = null;
  private syncPin: (() => void) | null = null;
  private refreshInFlight = false;
  private refreshPending = false;

  private requestRefresh(): void {
    if (this.destroyed) return;
    const root = this.rootMessage();
    if (this.loading() || this.refreshInFlight || !root) {
      this.refreshPending = true;
      return;
    }
    this.refreshPending = false;
    this.refreshInFlight = true;
    const rootId = root.threadRootMessageId ?? root.messageIdHeader ?? root.id;
    this.readTail(rootId, this.newestPage())
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.refreshInFlight = false;
          if (this.refreshPending) this.requestRefresh();
        }),
      )
      .subscribe({
        next: (tail) => this.applyTail(tail),
        // Silent: the event is a hint, the agent keeps the thread they already have.
        error: () => undefined,
      });
  }

  private loadTemplates(): void {
    this.emailService
      .getTemplates(0, 100)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.templates.set(response.content);
          this.filteredTemplates.set(response.content);
        },
        error: () => {
          // non-critical – templates are optional
        },
      });
  }

  /** Loads the page before the oldest one shown and prepends it (pages are read newest-first). */
  protected loadMoreMessages(): void {
    const root = this.rootMessage();
    const page = this.oldestPage() - 1;
    if (!root || this.loadingMore() || page < 0) return;
    this.stopPinning();
    this.loadingMore.set(true);
    const rootId = root.threadRootMessageId ?? root.messageIdHeader ?? root.id;
    this.emailService
      .getThread(rootId, page, THREAD_PAGE_SIZE)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.loadingMore.set(false)),
      )
      .subscribe({
        next: (response) => {
          // A refresh that reloaded the tail meanwhile moved the range: drop the stale page.
          if (page !== this.oldestPage() - 1) return;
          // Older messages never move (the list only grows at the end), but a message sitting on
          // a page boundary can be answered twice: merge by id.
          this.thread.update((current) => {
            const known = new Set(current.map((m) => m.id));
            return [...response.content.filter((m) => !known.has(m.id)), ...current];
          });
          this.oldestPage.set(page);
        },
        error: () => this.error.set(this.transloco.translate('agent.emailContact.errorThread')),
      });
  }

  protected onTemplateSelected(template: EmailTemplate): void {
    this.selectedTemplate.set(template);
    this.templateSearchControl.setValue(template.name, { emitEvent: false });
    this.filteredTemplates.set([]);

    if (template.variables.length > 0) {
      const vars: Record<string, string> = {};
      template.variables.forEach((v) => (vars[v] = ''));
      this.templateVariables.set(vars);
      this.showVariableForm.set(true);
    } else {
      this.applyTemplateViaApi(template, {});
    }
  }

  protected onVariableChange(variable: string, value: string): void {
    this.templateVariables.update((current) => ({ ...current, [variable]: value }));
  }

  protected previewTemplate(): void {
    const tpl = this.selectedTemplate();
    if (!tpl) return;
    this.applyTemplateViaApi(tpl, this.templateVariables());
  }

  private applyTemplateViaApi(template: EmailTemplate, variables: Record<string, string>): void {
    this.emailService
      .previewTemplate(template.id, variables, this.contactId())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (preview) => {
          this.setEditorHtml(preview.bodyHtml);
          this.subjectEdited.set(true);
          this.replySubject.set(preview.subject);
          this.showVariableForm.set(false);
        },
        error: () => {
          this.error.set(this.transloco.translate('agent.emailContact.errorTemplate'));
        },
      });
  }

  private setEditorHtml(html: string): void {
    // Template previews and restored drafts both go through Angular's HTML sanitizer before
    // they reach innerHTML (no scripts / event-handler attributes).
    const safe = this.sanitizeHtml(html);
    if (this.editorRef) {
      this.editorRef.nativeElement.innerHTML = safe;
    }
    this.replyHtml.set(safe);
  }

  private sanitizeHtml(html: string): string {
    return this.sanitizer.sanitize(SecurityContext.HTML, html) ?? '';
  }

  protected onSubjectInput(value: string): void {
    this.subjectEdited.set(true);
    this.replySubject.set(value);
  }

  protected onEditorInput(event: Event): void {
    const div = event.target as HTMLDivElement;
    this.replyHtml.set(div.innerHTML);
  }

  protected execCommand(command: string): void {
    document.execCommand(command, false);
  }

  protected insertLink(): void {
    const url = window.prompt('Podaj adres URL:');
    if (url) {
      document.execCommand('createLink', false, url);
    }
  }

  // ===== Attachment methods =====

  protected triggerFileInput(): void {
    this.fileInputRef?.nativeElement.click();
  }

  protected onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    if (!files.length) return;
    input.value = ''; // reset so same file can be re-selected

    this.uploading.set(true);
    this.uploadError.set(null);

    const uploadNext = (index: number): void => {
      if (index >= files.length) {
        this.uploading.set(false);
        return;
      }
      const file = files[index];
      this.emailService
        .uploadAttachment(file)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (resp) => {
            this.pendingAttachments.update((list) => [
              ...list,
              {
                s3Key: resp.s3Key,
                filename: resp.filename,
                contentType: resp.contentType,
                sizeBytes: resp.sizeBytes,
              },
            ]);
            uploadNext(index + 1);
          },
          error: () => {
            this.uploading.set(false);
            this.uploadError.set(
              this.transloco.translate('agent.emailContact.uploadFailed', { name: file.name }),
            );
          },
        });
    };

    uploadNext(0);
  }

  protected removeAttachment(s3Key: string): void {
    this.pendingAttachments.update((list) => list.filter((a) => a.s3Key !== s3Key));
  }

  protected formatFileSize(bytes: number): string {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  }

  // ===== Send / Cancel =====

  protected sendReply(): void {
    const root = this.rootMessage();
    if (!root || !this.canSend()) return;

    this.error.set(null);

    const request: SendReplyRequest = {
      bodyHtml: this.replyHtml(),
      subject: this.replySubject(),
      attachments: this.pendingAttachments().length > 0 ? this.pendingAttachments() : undefined,
    };

    const tpl = this.selectedTemplate();
    if (tpl) {
      request.templateId = tpl.id;
      const vars = this.templateVariables();
      if (Object.keys(vars).length > 0) {
        request.templateVariables = vars;
      }
    }

    // Owned by a root service, not by this component: a tab switch must not cancel the request
    // nor allow a second send (see EmailReplyService). Success closes the tab from there.
    this.emailReply.send(this.contactId(), root.id, request);
  }

  protected cancelReply(): void {
    // Abandon the contact on the backend before closing the tab. Silent for the global
    // interceptor, so a failure gets one clear message from ContactConflictService.
    this.contactService
      .abandonContact(this.contactId(), { silent: true })
      .pipe(
        map(() => true),
        catchError((err: unknown) => {
          if (isConflict(err)) {
            // Changed in parallel (BE-209): the contact is not abandoned, so the card and the
            // draft stay. If it turns out to be no longer the agent's, the card is closed from
            // there (with the draft text on the clipboard) and this component goes with it.
            void this.conflict.handle('agent.contactConflict.abandon', this.contactId());
            return of(false);
          }
          // Any other failure: the agent chose to leave, the UI closes regardless.
          void this.conflict.handle('agent.contactConflict.abandonFailed', this.contactId());
          return of(true);
        }),
      )
      .subscribe((close) => {
        if (!close) return;
        this.discardDraft();
        this.replySent.emit(false);
      });
  }

  private discardDraft(): void {
    this.draftClosed = true;
    this.drafts.clear(this.contactId());
  }

  protected getVariableKeys(): string[] {
    return Object.keys(this.templateVariables());
  }

  protected trackByMessageId(_index: number, msg: EmailMessage): string {
    return msg.id;
  }

  protected trackByTemplateId(_index: number, tpl: EmailTemplate): string {
    return tpl.id;
  }

  protected trackByKey(_index: number, key: string): string {
    return key;
  }

  protected trackByS3Key(_index: number, att: PendingAttachment): string {
    return att.s3Key;
  }
}
