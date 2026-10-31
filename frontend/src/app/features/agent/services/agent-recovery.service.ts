import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { HttpClient, HttpContext, HttpErrorResponse } from '@angular/common/http';
import { catchError, firstValueFrom, map, of, throwError, timeout } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { SKIP_ERROR_TOAST } from '../../../core/interceptors/error-handler.interceptor';
import { SoftphoneService } from './softphone.service';
import { ContactTabStore, TabLimitReason, tabLimitReason } from './contact-tab.store';
import { ContactDraftStore } from './contact-draft.store';
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
 *               plus `POST /contacts/{id}/accept` (once; ContactDraftStore remembers it).
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

  constructor() {
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
    const statusMark = this.status.statusSince();
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
    const outcome = this.apply(fetched, this.tabStore.closedSince(closeMark), statusMark);
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
            // An older backend does not know the path. Spring answers an unknown path with 404,
            // but this API's catch-all handler turns it into 500 (checked against the running
            // backend), so both mean "try the old endpoint". Safe because it is a read-only GET
            // whose answer (one ASSIGNED contact) only ever adds a card; if the old call fails
            // too, the error is swallowed below and the desktop is left alone.
            err instanceof HttpErrorResponse && (err.status === 404 || err.status === 500)
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
    statusMark: number | null,
  ): { opened: number; remaining: number; callNotRestored: number } {
    const live = this.softphoneService.session();
    const candidates: (Item & { type: ContactType })[] = [];
    const overflow: (OverflowContact & { index: number })[] = [];
    let opened = 0;
    let acwRestored = false;

    const park = (item: Item, reason: OverflowContact['reason']) =>
      overflow.push({
        contactId: item.contact.contactId,
        type: item.type,
        customerName: displayName(item.contact),
        phase: item.contact.phase,
        reason,
        index: item.index,
      });

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
      if (item.type === 'CHAT' || item.type === 'EMAIL') counts.async++;
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
    // An answer from the old endpoint is incomplete: it must not erase what the last full run found.
    const next = fetched.complete
      ? parked
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
   * started (`statusSince` moved): an automatic run (polling, list refresh) must never undo a
   * choice made meanwhile. Focus is not moved: it only follows an explicit agent action.
   * If the status has not been loaded yet (page reload race), the backend's status stands.
   * The call is silent on failure (no global toast): the agent did not ask for it.
   */
  private leaveAvailableForAcw(statusMark: number | null): void {
    if (this.status.currentStatus() !== 'AVAILABLE') return;
    if (this.status.statusSince() !== statusMark) return;
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
        this.acceptOnce(contact.contactId);
      }
    } else if (type !== 'PHONE') {
      // Already ACTIVE: the e-mail card must not send `accept` when it mounts (FE-131).
      this.drafts.markAccepted(contact.contactId);
    }
    return null;
  }

  /**
   * Tells the backend the agent has the contact (stops re-queueing); once per contact.
   * Also sent for a SOCIAL contact although the live CONTACT_ASSIGNED path never accepts one
   * (only the e-mail card does): `POST /accept` is channel-agnostic and idempotent, and
   * ContactAssignmentMonitor re-queues ASSIGNED contacts of every channel, so skipping it here
   * would only repeat the live path's gap (reported as a follow-up).
   */
  private acceptOnce(contactId: string): void {
    if (!this.drafts.markAccepted(contactId)) return;
    this.http
      .post(`${environment.apiUrl}/contacts/${contactId}/accept`, {})
      .pipe(
        catchError(() => {
          // Fire-and-forget: the card's first mount retries because the flag is reverted.
          this.drafts.unmarkAccepted(contactId);
          return of(null);
        }),
      )
      .subscribe();
  }
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
    customerId: contact.customerId ?? undefined,
  };
}
