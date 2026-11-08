import { Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { HttpClient, HttpContext, HttpErrorResponse } from '@angular/common/http';
import { catchError, firstValueFrom, map, of, throwError, timeout } from 'rxjs';
import { TranslocoService } from '@jsverse/transloco';
import { environment } from '../../../../environments/environment';
import { SKIP_ERROR_TOAST } from '../../../core/interceptors/error-handler.interceptor';
import { AuthService } from '../../../core/services/auth.service';
import { NotificationService } from '../../../core/services/notification.service';
import { ContactService } from './contact.service';
import { SoftphoneService } from './softphone.service';
import { ContactResponse } from '../../../core/models/contact.model';
import { ContactTabStore, TabLimitReason, tabLimitReason } from './contact-tab.store';
import { ContactDraftStore } from './contact-draft.store';
import { AsyncContactAcceptService, isAsyncAcceptable } from './async-contact-accept.service';
import { AgentStatusService } from './agent-status.service';
import {
  ActiveContactResponse,
  OverflowContact,
  RecoverySummary,
} from '../models/active-contact.model';
import { CallDirection, ContactTab, ContactType } from '../models/contact-tab.model';
import { ContactAssignedPayload } from '../models/ws-event.model';

/** Legacy `GET /api/agent/me/assigned-contact` body (used only against an older backend). */
export interface AssignedContactResponse {
  contactId: string;
  /** Contact channel: 'PHONE' | 'CHAT' | 'EMAIL' | 'SOCIAL' */
  channel: string;
  customerIdentifier: string;
  customerName: string;
  queueName?: string;
  customerId?: string;
}

/** The desktop must stay usable when the endpoint hangs. */
export const RECOVERY_TIMEOUT_MS = 10_000;

/** While a PHONE card without a live call exists, the backend is asked again this often. */
export const CALL_NOT_RESTORED_POLL_MS = 20_000;

/**
 * Rebuilds the agent desktop from the backend after a page reload, a re-login or a WebSocket
 * reconnect (events sent during the outage are lost).
 *
 * One call to `GET /api/agent/me/active-contacts` returns every contact the agent still owns;
 * each becomes a card according to its `phase`:
 * - RINGING     PHONE: ringing softphone session as for a fresh assignment. EMAIL/SOCIAL/CHAT: card
 *               plus `POST /contacts/{id}/accept` (AsyncContactAcceptService: once per contact).
 * - IN_PROGRESS the card only, never `accept` again (the contact is already ACTIVE). A PHONE card
 *               has no live call: the WebRTC leg died with the page, so it is flagged
 *               `callNotRestored` and explains that instead of offering call controls.
 * - WRAP_UP     PHONE card in ACW (disposition form); its timer starts at `wrapUpStartedAt`.
 *
 * Tab limits are respected without losing anything: contacts that do not fit are listed in
 * `overflow` (shown by cc-recovery-overflow-notice). Open contacts get a slot before ACW ones
 * (a ringing call must not lose the single PHONE slot to an old ACW) but cards are opened in the
 * backend's order (`assignedAt`).
 *
 * Nothing is written to browser storage (drafts are in memory only, GDPR). A failing or hanging
 * endpoint never blocks the desktop; a 404 (older backend) falls back to `assigned-contact`.
 */
@Injectable({ providedIn: 'root' })
export class AgentRecoveryService {
  private readonly http = inject(HttpClient);
  private readonly softphoneService = inject(SoftphoneService);
  private readonly tabStore = inject(ContactTabStore);
  private readonly drafts = inject(ContactDraftStore);
  private readonly status = inject(AgentStatusService);
  private readonly asyncAccept = inject(AsyncContactAcceptService);
  private readonly contacts = inject(ContactService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly auth = inject(AuthService);

  /** Contacts of this agent that have no card because of the tab limits (until the next run). */
  readonly overflow = signal<OverflowContact[]>([]);
  /** True while a recovery request is in flight (drives the retry buttons). */
  readonly loading = signal(false);
  /** Outcome of the last finished run (null before the first one / after reset). */
  readonly lastRun = signal<RecoverySummary | null>(null);

  /** Bumped by reset(); an answer that belongs to an older session is discarded. */
  private generation = 0;
  private inFlight: Promise<void> | null = null;
  private runId = 0;
  private manualSource: RecoverySummary['source'] | null = null;
  /** Position in the park log: a run keeps what was parked after it started (see apply()). */
  private parkSeq = 0;
  private readonly parkedAtSeq = new Map<string, number>();
  /** Parked contacts whose state is being checked before they get a card. */
  private readonly promoting = new Set<string>();

  /** Overflow contacts being returned to the queue right now (drives the row buttons). */
  readonly returningIds = signal<ReadonlySet<string>>(new Set());

  constructor() {
    // A slot freed (card closed, returned, disposition saved): the first parked async contacts
    // that fit get their card, in the backend's order. Reads both signals, so it also runs when
    // an entry is parked; then nothing fits and nothing happens.
    // `returningIds` is tracked too: a failed release frees the row, and with a free slot it must
    // get its card now, not at the next unrelated change of the tabs or the list.
    effect(() => {
      this.tabStore.tabs();
      this.overflow();
      this.returningIds();
      untracked(() => this.promoteFromOverflow());
    });
    // A PHONE card whose call cannot be restored has no event that would end it: ask the backend
    // now and then, only while such a card exists. The effect reads only the boolean below, so
    // other tab changes never restart the 20 s timer; it is torn down with the card (closed, ACW,
    // tabs reset on logout), so nothing polls outside the situation that needs it.
    effect((onCleanup) => {
      if (!this.needsPolling()) return;
      const timer = setInterval(() => void this.recoverAfterReconnect(), CALL_NOT_RESTORED_POLL_MS);
      onCleanup(() => clearInterval(timer));
    });
  }

  private readonly hasNotRestored = computed(() =>
    this.tabStore.tabs().some((t) => t.callNotRestored),
  );

  /**
   * Polling is needed while a "not restored" card is shown, and also while one is hidden: its
   * call can end any time and the disposition card must then come back. It stops by itself when
   * the hidden set empties (the contact reached WRAP_UP, vanished from the backend, or reset()).
   */
  private readonly needsPolling = computed(
    () => this.hasNotRestored() || this.dismissedCount() > 0,
  );

  /**
   * "Not restored" PHONE contacts the agent hid this session. They stay hidden while the backend
   * still says IN_PROGRESS (the card would only block the single phone slot again) and come
   * back as soon as the call has ended (WRAP_UP), because the disposition is then required.
   * Memory only, cleared with the session.
   */
  private readonly dismissed = new Set<string>();
  private readonly dismissedCount = signal(0);

  /** The agent closed a "call not restored" card: do not reopen it while the call is open. */
  dismissNotRestored(contactId: string): void {
    this.dismissed.add(contactId);
    this.dismissedCount.set(this.dismissed.size);
  }

  /** Called when the agent session ends: forget the overflow and ignore any pending answer. */
  reset(): void {
    this.generation++;
    this.inFlight = null;
    this.manualSource = null;
    this.loading.set(false);
    this.overflow.set([]);
    this.lastRun.set(null);
    this.dismissed.clear();
    this.dismissedCount.set(0);
    this.returningIds.set(new Set());
    this.parkedAtSeq.clear();
    this.promoting.clear();
  }

  // ── Overflow: contacts the backend assigned that do not fit the card strip ─────────────────
  // The backend decides who gets a contact (BE-156); the desktop never refuses one. A contact
  // without a free slot is parked here and CONFIRMED to the backend like any card (accept), so
  // ContactAssignmentMonitor does not re-send and finally re-queue it. It gets its card when a
  // slot frees up (promoteFromOverflow) or goes back to the queue by the agent's choice.

  isParked(contactId: string): boolean {
    return this.overflow().some((o) => o.contactId === contactId);
  }

  /**
   * Parks a contact from a live CONTACT_ASSIGNED event whose card was refused by the layout
   * capacity, and confirms it. Idempotent: a re-delivery only retries a failed accept.
   */
  parkAssigned(payload: ContactAssignedPayload, reason: Exclude<TabLimitReason, null>): void {
    if (!this.isParked(payload.contactId)) {
      this.parkedAtSeq.set(payload.contactId, ++this.parkSeq);
      this.overflow.update((list) => [
        ...list,
        {
          contactId: payload.contactId,
          type: payload.type,
          customerName: payload.customerName || payload.customerIdentifier,
          phase: 'RINGING',
          reason,
          customerIdentifier: payload.customerIdentifier,
          customerId: payload.customerId,
          queueName: payload.queueName,
          queueId: payload.queueId,
          assignedAt: toDate(payload.assignedAt)?.getTime() ?? Date.now(),
        },
      ]);
    }
    this.asyncAccept.acceptOpened(payload.type, payload.contactId);
  }

  /**
   * "Return to queue" for a parked contact: `POST /contacts/{id}/release`. The entry leaves the
   * list only after the backend confirmed (or said it is no longer this agent's: 404/409, which
   * retrying cannot fix). Any other error keeps it and says so.
   */
  async returnToQueue(contactId: string): Promise<void> {
    const item = this.overflow().find((o) => o.contactId === contactId);
    if (!item || item.type === null || this.returningIds().has(contactId)) return;
    const generation = this.generation;
    this.returningIds.update((ids) => new Set(ids).add(contactId));
    // While the request is pending the backend may hand the same contact back (see the store).
    this.tabStore.beginRelease(contactId);
    let gone = false;
    let failed = false;
    try {
      await firstValueFrom(this.contacts.releaseContact(contactId));
    } catch (err) {
      const status = (err as { status?: number } | null)?.status;
      if (status === 404 || status === 409) gone = true;
      else failed = true;
    }
    const reassigned = this.tabStore.endRelease(contactId);
    if (generation !== this.generation) return; // the session ended meanwhile
    this.returningIds.update((ids) => {
      const next = new Set(ids);
      next.delete(contactId);
      return next;
    });
    const name = item.customerName;
    if (failed) {
      if (!reassigned) {
        this.notifications.error(
          this.transloco.translate('agent.recoveryOverflow.returnError', { name }),
        );
      }
      return; // still the agent's: stays in the list
    }
    this.overflow.update((list) => list.filter((o) => o.contactId !== contactId));
    this.drafts.clear(contactId);
    this.tabStore.markContactClosed(contactId);
    if (reassigned) {
      // Handed back before the answer arrived: it is a new assignment, never lose it.
      if (this.tabStore.openFromContactAssigned(reassigned) !== null) {
        this.parkAssigned(reassigned, 'MAX_TOTAL');
      }
      return;
    }
    this.notifications.success(
      this.transloco.translate(
        gone ? 'agent.desktop.tabClose.notYours' : 'agent.desktop.tabClose.returned',
        { name },
      ),
    );
  }

  /**
   * Opens a card for the parked async contacts that fit now, in list order (the backend's
   * assignedAt order). Never steals the active tab (openTab adds inactive unless it is the first).
   * PHONE entries are left to "check again": they need the softphone session logic.
   *
   * A parked entry is only a local note: the contact may have been closed or taken over meanwhile
   * without any event. So each candidate is checked against the backend first (`GET /contacts/{id}`);
   * a dead one leaves the list with one short notice instead of becoming a dead card. When the
   * check itself fails the card is opened anyway: the backend stays the judge of later actions.
   */
  private promoteFromOverflow(): void {
    if (this.overflow().length === 0) return;
    const counts = this.tabStore.tabCounts();
    const candidates: OverflowContact[] = [];
    for (const item of this.overflow()) {
      if (item.type === null || item.type === 'PHONE') continue;
      if (this.returningIds().has(item.contactId)) continue;
      if (this.promoting.has(item.contactId)) continue;
      if (this.tabStore.tabs().some((t) => t.contactId === item.contactId)) continue;
      if (tabLimitReason(counts, item.type) !== null) continue;
      counts.total++;
      candidates.push(item);
    }
    if (candidates.length === 0) return;
    const generation = this.generation;
    for (const c of candidates) this.promoting.add(c.contactId);
    void Promise.all(candidates.map((c) => this.promoteOne(c, generation))).then((outcomes) => {
      if (generation !== this.generation) return;
      this.announce(
        'promoted',
        outcomes.flatMap((o, i) => (o === 'opened' ? [candidates[i].customerName] : [])),
      );
      this.announce(
        'vanished',
        outcomes.flatMap((o, i) => (o === 'gone' ? [candidates[i].customerName] : [])),
      );
    });
  }

  private async promoteOne(
    item: OverflowContact,
    generation: number,
  ): Promise<'opened' | 'gone' | 'kept'> {
    const type = item.type as ContactType;
    const verdict = await this.fetchVerdict(item.contactId, type);
    this.promoting.delete(item.contactId);
    // Session ended, row returned or removed while the check ran: nothing to do.
    if (generation !== this.generation || !this.isParked(item.contactId)) return 'kept';
    if (this.returningIds().has(item.contactId)) return 'kept';
    if (verdict === 'gone') {
      this.removeFromOverflow(item.contactId);
      this.drafts.clear(item.contactId);
      this.tabStore.markContactClosed(item.contactId);
      return 'gone';
    }
    const refused = this.tabStore.openTab({
      id: crypto.randomUUID(),
      type,
      contactId: item.contactId,
      customerName: item.customerName,
      customerIdentifier: item.customerIdentifier ?? item.customerName,
      status: 'ACTIVE',
      startedAt: new Date(item.assignedAt ?? Date.now()),
      customerId: item.customerId,
    });
    if (refused !== null) return 'kept'; // another card took the slot; stays listed
    if (item.phase === 'RINGING') {
      // Guarded: sends nothing when the accept went out at parking time.
      this.asyncAccept.acceptOpened(type, item.contactId);
    } else {
      this.drafts.markAccepted(item.contactId);
    }
    this.removeFromOverflow(item.contactId);
    return 'opened';
  }

  private removeFromOverflow(contactId: string): void {
    this.parkedAtSeq.delete(contactId);
    this.overflow.update((list) => list.filter((o) => o.contactId !== contactId));
  }

  /** One toast per batch: a series of promotions or dead entries must not stack toasts. */
  private announce(kind: 'promoted' | 'vanished', names: string[]): void {
    if (names.length === 0) return;
    const key =
      names.length === 1 ? `agent.recoveryOverflow.${kind}` : `agent.recoveryOverflow.${kind}Many`;
    this.notifications.info(this.transloco.translate(key, { name: names[0], count: names.length }));
  }

  // ── Checking one contact against the backend ───────────────────────────────────────────────

  /** Backend's answer about one contact for this agent: still theirs, gone, or could not tell. */
  private async fetchVerdict(contactId: string, type: ContactType): Promise<ContactVerdict> {
    return (await this.inspect(contactId, type)).verdict;
  }

  private async inspect(
    contactId: string,
    type: ContactType,
  ): Promise<{ verdict: ContactVerdict; contact: ContactResponse | null }> {
    try {
      const body = await firstValueFrom(
        this.contacts.getContact(contactId, { silent: true }).pipe(timeout(RECOVERY_TIMEOUT_MS)),
      );
      const contact = body && typeof body === 'object' ? body : null;
      return { verdict: this.verdictOf(contact, type), contact };
    } catch (err) {
      const gone = (err as { status?: number } | null)?.status === 404;
      return { verdict: gone ? 'gone' : 'unknown', contact: null };
    }
  }

  private verdictOf(body: unknown, type: ContactType): ContactVerdict {
    const c = body as Partial<ContactResponse> | null;
    if (!c || typeof c !== 'object' || typeof c.status !== 'string') return 'unknown';
    const me = this.auth.currentUserId();
    if (c.agentId && me && c.agentId !== me) return 'gone';
    switch (c.status) {
      case 'QUEUED':
      case 'IVR':
      case 'ABANDONED':
      case 'FAILED':
      case 'TRANSFERRED':
        return 'gone';
      case 'COMPLETED':
        // A finished call still waits for its disposition; any other finished contact is done.
        return type === 'PHONE' && !c.dispositionCode ? 'mine' : 'gone';
      default:
        return 'mine';
    }
  }

  /**
   * HTTP 409 on a contact action: the contact changed in the backend. Reads its real state and
   * brings the desktop in line: a contact that is no longer this agent's loses its card (its
   * notes go to the clipboard first, since the draft is dropped with the card); one that still is
   * has its card updated (a finished call enters ACW). Returns what was found, for the message.
   * Never rejects.
   */
  async reconcileContact(contactId: string): Promise<ReconcileOutcome> {
    const generation = this.generation;
    const tab = this.tabStore.tabs().find((t) => t.contactId === contactId);
    const type: ContactType | null =
      tab?.type ?? this.overflow().find((o) => o.contactId === contactId)?.type ?? null;
    if (type === null) return { verdict: 'unknown', closed: false, notesCopied: false };
    // A live call owns the line: its end is signalled by the softphone, not guessed from here.
    if (this.softphoneService.session()?.contactId === contactId) {
      return { verdict: 'mine', closed: false, notesCopied: false };
    }
    const { verdict, contact } = await this.inspect(contactId, type);
    if (generation !== this.generation)
      return { verdict: 'unknown', closed: false, notesCopied: false };
    const current = this.tabStore.tabs().find((t) => t.contactId === contactId);
    if (verdict === 'gone') {
      const notesCopied = await this.copyNotes(contactId);
      this.removeFromOverflow(contactId);
      this.dismissed.delete(contactId);
      this.dismissedCount.set(this.dismissed.size);
      if (current) this.tabStore.closeByContactId(contactId);
      else this.tabStore.markContactClosed(contactId);
      return { verdict, closed: current !== undefined, notesCopied };
    }
    if (
      verdict === 'mine' &&
      current?.type === 'PHONE' &&
      current.status !== 'WRAPPING' &&
      contact?.status === 'COMPLETED'
    ) {
      // The call ended while the card still shows it: it is in ACW now.
      this.tabStore.markAsWrapping(current.id, toDate(contact.endedAt)?.getTime());
    }
    return { verdict, closed: false, notesCopied: false };
  }

  /** Plain text of whatever the agent typed for the contact, to the clipboard (best effort). */
  private async copyNotes(contactId: string): Promise<boolean> {
    const parts = [
      this.drafts.dispositionDraft(contactId)?.notes ?? '',
      this.drafts.phoneNote(contactId),
      this.drafts.socialReply(contactId),
      htmlToText(this.drafts.emailDraft(contactId)?.bodyHtml ?? ''),
    ]
      .map((p) => p.trim())
      .filter((p) => p.length > 0);
    if (parts.length === 0) return false;
    try {
      await navigator.clipboard.writeText(parts.join('\n\n'));
      return true;
    } catch {
      return false;
    }
  }

  /**
   * "Check again" pressed by the agent (on the overflow notice or on a call card). Same run as
   * the automatic one, but its result is reported back to the agent.
   */
  checkAgain(source: 'overflow' | 'card'): Promise<void> {
    this.manualSource = source;
    return this.recoverAfterReconnect();
  }

  /** Never rejects. Concurrent calls share one request. */
  recoverAfterReconnect(): Promise<void> {
    if (this.inFlight) return this.inFlight;
    const run = this.run(this.generation).catch((err) => {
      console.warn('[AgentRecovery] Recovery failed:', err);
    });
    this.inFlight = run;
    void run.finally(() => {
      if (this.inFlight === run) this.inFlight = null;
    });
    return run;
  }

  private async run(generation: number): Promise<void> {
    this.loading.set(true);
    // Local actions taken while the request is in flight (reject, disposition, release, close)
    // make its answer stale for those contacts: they must not come back.
    const closeMark = this.tabStore.closeMark();
    const parkMark = this.parkSeq;
    const statusMark = this.status.statusEpisode();
    let fetched: Fetched | null;
    try {
      fetched = await this.fetchActiveContacts();
    } finally {
      if (generation === this.generation) this.loading.set(false);
    }
    if (generation !== this.generation) return;
    const source = this.manualSource;
    this.manualSource = null;
    const id = ++this.runId;
    if (fetched === null) {
      this.lastRun.set({
        id,
        ok: false,
        degraded: false,
        opened: 0,
        remaining: this.overflow().length,
        callNotRestored: 0,
        source,
      });
      return;
    }
    const outcome = this.apply(fetched, this.tabStore.closedSince(closeMark), statusMark, parkMark);
    this.lastRun.set({ id, ok: true, degraded: !fetched.complete, ...outcome, source });
  }

  // ── Fetching ───────────────────────────────────────────────────────────────

  /** null = could not load (error / timeout): leave the desktop as it is. */
  private async fetchActiveContacts(): Promise<Fetched | null> {
    const context = new HttpContext().set(SKIP_ERROR_TOAST, true);
    return firstValueFrom(
      this.http
        .get<ActiveContactResponse[]>(`${environment.apiUrl}/agent/me/active-contacts`, {
          context,
        })
        .pipe(
          timeout(RECOVERY_TIMEOUT_MS),
          map((body): Fetched => ({ contacts: Array.isArray(body) ? body : [], complete: true })),
          catchError((err: unknown) =>
            // An older backend does not know the path and answers 404 (BE-161: an unknown path is
            // a 404 problem+json, no longer swallowed into a 500). Only that means "try the old
            // endpoint"; a 500 is a real error and takes the non-blocking failure path below.
            // Safe because it is a read-only GET whose answer (one ASSIGNED contact) only ever
            // adds a card; if the old call fails too, the error is swallowed below.
            err instanceof HttpErrorResponse && err.status === 404
              ? this.fetchLegacyAssigned(context)
              : throwError(() => err),
          ),
          catchError((err: unknown) => {
            console.warn('[AgentRecovery] Could not load active contacts:', err);
            return of(null);
          }),
        ),
    );
  }

  /** Older backend without the new endpoint: only one ASSIGNED contact (RINGING) is known. */
  private fetchLegacyAssigned(context: HttpContext) {
    return this.http
      .get<AssignedContactResponse>(`${environment.apiUrl}/agent/me/assigned-contact`, {
        observe: 'response',
        context,
      })
      .pipe(
        timeout(RECOVERY_TIMEOUT_MS),
        map((resp): Fetched => {
          const c = resp.status === 200 ? resp.body : null;
          if (!c) return { contacts: [], complete: false };
          return {
            complete: false,
            contacts: [
              {
                contactId: c.contactId,
                channel: c.channel,
                status: 'ASSIGNED',
                phase: 'RINGING',
                customerId: c.customerId,
                customerIdentifier: c.customerIdentifier,
                customerName: c.customerName,
                queueName: c.queueName,
              },
            ],
          };
        }),
      );
  }

  // ── Applying ───────────────────────────────────────────────────────────────

  private apply(
    fetched: Fetched,
    closedSince: ReadonlySet<string>,
    statusMark: number,
    parkMark: number,
  ): { opened: number; remaining: number; callNotRestored: number } {
    const live = this.softphoneService.session();
    const candidates: (Item & { type: ContactType })[] = [];
    const overflow: (OverflowContact & { index: number })[] = [];
    let opened = 0;
    let acwRestored = false;

    const park = (item: Item, reason: OverflowContact['reason']): void => {
      overflow.push({
        contactId: item.contact.contactId,
        type: item.type,
        customerName: displayName(item.contact),
        phase: item.contact.phase,
        reason,
        customerIdentifier: item.contact.customerIdentifier,
        customerId: item.contact.customerId ?? undefined,
        queueName: item.contact.queueName ?? undefined,
        queueId: item.contact.queueId ?? undefined,
        assignedAt: toDate(item.contact.assignedAt ?? item.contact.startedAt)?.getTime(),
        index: item.index,
      });
      // Parked, but the backend still waits for the agent to confirm an async contact.
      if (item.type !== null && item.contact.phase === 'RINGING') {
        this.asyncAccept.acceptOpened(item.type, item.contact.contactId);
      }
    };

    fetched.contacts.forEach((contact, index) => {
      const item: Item = { contact, type: toContactType(contact.channel), index };
      // Closed, rejected, released or finished here after the request left: the answer is stale.
      if (closedSince.has(contact.contactId)) return;
      if (item.type === null) {
        console.warn('[AgentRecovery] Unknown channel:', contact.channel);
        park(item, 'UNSUPPORTED_CHANNEL');
        return;
      }
      const type = item.type;
      const existing = this.tabStore.tabs().find((t) => t.contactId === contact.contactId);
      if (existing) {
        if (this.refreshExisting(existing, contact, live?.contactId === contact.contactId)) {
          opened++;
          acwRestored = true;
        }
        return;
      }
      // Hidden by the agent while the call is still open; a finished call needs its disposition.
      if (type === 'PHONE' && this.dismissed.has(contact.contactId)) {
        if (contact.phase !== 'WRAP_UP') return;
        this.dismissed.delete(contact.contactId);
        this.dismissedCount.set(this.dismissed.size);
      }
      // A live softphone session owns the single phone line: it wins over any recovered call.
      if (type === 'PHONE' && live !== null && contact.phase !== 'WRAP_UP') {
        park(item, 'OTHER_CALL_ACTIVE');
        return;
      }
      // Being returned to the queue right now: the card is re-opened when the release ends.
      if (type !== 'PHONE' && this.tabStore.noteAssignedDuringRelease(toPayload(contact, type))) {
        return;
      }
      candidates.push({ ...item, type });
    });

    // A hidden contact that a complete answer no longer lists is gone (ended, taken elsewhere):
    // forget it, otherwise polling would run for it forever.
    if (fetched.complete && this.dismissed.size > 0) {
      const listed = new Set(fetched.contacts.map((c) => c.contactId));
      for (const id of [...this.dismissed]) if (!listed.has(id)) this.dismissed.delete(id);
      this.dismissedCount.set(this.dismissed.size);
    }

    // Plan the slots. A phone contact (any phase, an open call before an ACW) goes first: its
    // disposition or ringing call cannot be postponed, and there is only one phone slot. The rest
    // follow in the backend's order. Cards are then opened in the backend's order (assignedAt).
    const counts = this.tabStore.tabCounts();
    const phoneRank = (c: { contact: ActiveContactResponse; type: ContactType }) =>
      c.type !== 'PHONE' ? 2 : c.contact.phase === 'WRAP_UP' ? 1 : 0;
    const byPriority = [...candidates].sort(
      (a, b) => phoneRank(a) - phoneRank(b) || a.index - b.index,
    );
    const admitted = new Set<string>();
    for (const item of byPriority) {
      const reason = tabLimitReason(counts, item.type);
      if (reason !== null) {
        park(item, reason);
        continue;
      }
      admitted.add(item.contact.contactId);
      counts.total++;
      if (item.type === 'PHONE') counts.phone++;
    }
    for (const item of candidates) {
      if (!admitted.has(item.contact.contactId)) continue;
      const refused = this.openCard(item.contact, item.type);
      if (refused === null) {
        opened++;
        if (item.contact.phase === 'WRAP_UP') acwRestored = true;
      } else {
        park(item, refused); // planned to fit, but another tab won the race
      }
    }

    overflow.sort((a, b) => a.index - b.index);
    const parked = overflow.map(({ index: _index, ...rest }) => rest);
    // The answer is a snapshot from before it arrived. Entries parked live after the request
    // left (and rows being returned right now) are newer than it, so a complete answer must not
    // erase them: that would leave an accepted contact with neither a card nor a row.
    const answered = new Set(parked.map((p) => p.contactId));
    const newerThanRun = this.overflow().filter(
      (o) =>
        !answered.has(o.contactId) &&
        !closedSince.has(o.contactId) &&
        !this.tabStore.tabs().some((t) => t.contactId === o.contactId) &&
        ((this.parkedAtSeq.get(o.contactId) ?? 0) > parkMark ||
          this.returningIds().has(o.contactId)),
    );
    // An answer from the old endpoint is incomplete: it must not erase what the last full run found.
    const next = fetched.complete
      ? [...parked, ...newerThanRun]
      : [
          ...this.overflow().filter(
            (o) =>
              !parked.some((p) => p.contactId === o.contactId) &&
              !this.tabStore.tabs().some((t) => t.contactId === o.contactId),
          ),
          ...parked,
        ];
    this.overflow.set(next);

    if (acwRestored) this.leaveAvailableForAcw(statusMark);
    return {
      opened,
      remaining: next.length,
      callNotRestored: this.tabStore.tabs().filter((t) => t.callNotRestored).length,
    };
  }

  /**
   * An ACW card is back, so the agent is in after-contact work. The normal path sets AFTER_CONTACT
   * when the call ends; here only an AVAILABLE agent is moved (a deliberate BREAK or the
   * backend's own AFTER_CONTACT stay), otherwise a new call would arrive on a phone slot that
   * the ACW card occupies. Not when the agent changed the status themselves since the run
   * started (a new status episode began): an automatic run (polling, list refresh) must never
   * undo a choice made meanwhile. Focus is not moved: it only follows an explicit agent action.
   * If the status has not been loaded yet (page reload race), the backend's status stands.
   * The call is silent on failure (no global toast): the agent did not ask for it.
   */
  private leaveAvailableForAcw(statusMark: number): void {
    if (this.status.currentStatus() !== 'AVAILABLE') return;
    if (this.status.statusEpisode() !== statusMark) return;
    this.status
      .changeStatus('AFTER_CONTACT', { silent: true })
      .subscribe({ error: () => undefined });
  }

  /** The card is already open: only ACW can still change it. True when it entered ACW now. */
  private refreshExisting(
    tab: ContactTab,
    contact: ActiveContactResponse,
    hasLiveSession: boolean,
  ): boolean {
    if (tab.type !== 'PHONE' || contact.phase !== 'WRAP_UP') return false;
    if (tab.status === 'WRAPPING' || hasLiveSession) return false;
    // Same timer origin as a freshly opened ACW card: when the backend says the call ended.
    this.tabStore.markAsWrapping(
      tab.id,
      toDate(contact.wrapUpStartedAt ?? contact.endedAt)?.getTime(),
    );
    return true;
  }

  /** null when the card was opened, otherwise the limit that refused it. */
  private openCard(contact: ActiveContactResponse, type: ContactType): TabLimitReason {
    const tab: Omit<ContactTab, 'isActive'> = {
      id: crypto.randomUUID(),
      type,
      contactId: contact.contactId,
      customerName: displayName(contact),
      customerIdentifier: contact.customerIdentifier,
      status: 'ACTIVE',
      startedAt: toDate(contact.startedAt ?? contact.assignedAt) ?? new Date(),
      direction: type === 'PHONE' ? toDirection(contact.direction) : undefined,
      customerId: contact.customerId ?? undefined,
    };

    if (contact.phase === 'WRAP_UP') {
      tab.status = 'WRAPPING';
      tab.wrappingSince =
        toDate(contact.wrapUpStartedAt ?? contact.endedAt)?.getTime() ?? Date.now();
    } else if (type === 'PHONE' && contact.phase === 'IN_PROGRESS') {
      tab.callNotRestored = true;
    }

    const refused = this.tabStore.openTab(tab);
    if (refused !== null) return refused;

    if (contact.phase === 'RINGING') {
      if (type === 'PHONE') {
        this.softphoneService.incomingCall(toPayload(contact, type));
      } else {
        this.asyncAccept.acceptOpened(type, contact.contactId);
      }
    } else if (isAsyncAcceptable(type)) {
      // Already ACTIVE: the e-mail card must not send `accept` when it mounts (FE-131).
      this.drafts.markAccepted(contact.contactId);
    }
    return null;
  }
}

type ContactVerdict = 'mine' | 'gone' | 'unknown';

/** What reconcileContact found and did, so the caller can word its message truthfully. */
export interface ReconcileOutcome {
  verdict: ContactVerdict;
  /** The card of a contact that is no longer the agent's was closed. */
  closed: boolean;
  /** The agent's unsent text was put on the clipboard before the card was closed. */
  notesCopied: boolean;
}

interface Item {
  contact: ActiveContactResponse;
  type: ContactType | null;
  index: number;
}

interface Fetched {
  contacts: ActiveContactResponse[];
  /** False for the old endpoint, which only knows one ASSIGNED contact. */
  complete: boolean;
}

function toContactType(channel: string): ContactType | null {
  if (channel === 'PHONE' || channel === 'EMAIL' || channel === 'CHAT') return channel;
  if (channel === 'SOCIAL' || channel.startsWith('SOCIAL_')) return 'SOCIAL';
  return null;
}

function htmlToText(html: string): string {
  if (!html) return '';
  return new DOMParser().parseFromString(html, 'text/html').body.textContent ?? '';
}

function toDirection(direction: string | null | undefined): CallDirection {
  return direction === 'OUTBOUND' ? 'OUTBOUND' : 'INBOUND';
}

function toDate(iso: string | null | undefined): Date | null {
  if (!iso) return null;
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? null : d;
}

function displayName(contact: ActiveContactResponse): string {
  return contact.customerName || contact.customerIdentifier;
}

function toPayload(contact: ActiveContactResponse, type: ContactType): ContactAssignedPayload {
  return {
    contactId: contact.contactId,
    type,
    customerName: displayName(contact),
    customerIdentifier: contact.customerIdentifier,
    queueName: contact.queueName ?? undefined,
    queueId: contact.queueId ?? undefined,
    customerId: contact.customerId ?? undefined,
    assignedAt: contact.assignedAt ?? null,
  };
}
