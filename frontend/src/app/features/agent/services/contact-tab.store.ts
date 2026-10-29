import { Injectable, computed, inject, signal } from '@angular/core';
import { ContactDraftStore } from './contact-draft.store';
import {
  CallDirection,
  ContactTab,
  ContactTabStatus,
  ContactType,
} from '../models/contact-tab.model';
import { ContactAssignedPayload, CallIncomingPayload } from '../models/ws-event.model';
import { normalizeContactName } from '../models/contact-name';

export const MAX_PHONE_TABS = 1;
export const MAX_ASYNC_TABS = 3;
export const MAX_TOTAL_TABS = 4;

export type TabLimitReason = 'MAX_PHONE' | 'MAX_ASYNC' | 'MAX_TOTAL' | null;

@Injectable({ providedIn: 'root' })
export class ContactTabStore {
  private readonly drafts = inject(ContactDraftStore);

  readonly tabs = signal<ContactTab[]>([]);

  readonly activeTab = computed<ContactTab | null>(
    () => this.tabs().find((t) => t.isActive) ?? this.tabs()[0] ?? null,
  );

  /** Returns the active tab if it is in WRAPPING (ACW) state, otherwise null */
  readonly wrappingTab = computed<ContactTab | null>(() => {
    const active = this.activeTab();
    return active?.status === 'WRAPPING' ? active : null;
  });

  readonly phoneTabCount = computed(() => this.tabs().filter((t) => t.type === 'PHONE').length);
  readonly asyncTabCount = computed(
    () => this.tabs().filter((t) => t.type === 'CHAT' || t.type === 'EMAIL').length,
  );
  readonly totalTabCount = computed(() => this.tabs().length);

  openFromCallIncoming(payload: CallIncomingPayload): TabLimitReason {
    return this.openTab({
      id: crypto.randomUUID(),
      type: 'PHONE',
      contactId: payload.contactId,
      customerName: payload.customerName,
      customerIdentifier: payload.customerPhone,
      status: 'ACTIVE',
      isActive: false,
      startedAt: new Date(),
      direction: 'INBOUND' as CallDirection,
    });
  }

  openFromCallOutbound(payload: CallIncomingPayload): TabLimitReason {
    return this.openTab({
      id: crypto.randomUUID(),
      type: 'PHONE',
      contactId: payload.contactId,
      customerName: payload.customerName,
      customerIdentifier: payload.customerPhone,
      status: 'ACTIVE',
      isActive: false,
      startedAt: new Date(),
      direction: 'OUTBOUND' as CallDirection,
      customerId: payload.customerId,
    });
  }

  openFromContactAssigned(payload: ContactAssignedPayload): TabLimitReason {
    return this.openTab({
      id: crypto.randomUUID(),
      type: payload.type,
      contactId: payload.contactId,
      customerName: payload.customerName,
      customerIdentifier: payload.customerIdentifier,
      status: 'ACTIVE',
      isActive: false,
      startedAt: new Date(),
      // PHONE contacts assigned via routing engine are always inbound
      direction: payload.type === 'PHONE' ? ('INBOUND' as CallDirection) : undefined,
      customerId: payload.customerId,
    });
  }

  /**
   * Adds a tab. A new contact never steals the active tab from an agent who is working on
   * another one (FE-133): the tab is added inactive. It becomes active only when
   * - there is no tab yet (the first one is active), or
   * - the caller explicitly passes `isActive: true` (an explicit agent action).
   * A ringing call is activated by the desktop's ringing effect / the incoming-call banner, not here.
   */
  openTab(tab: Omit<ContactTab, 'isActive'> & { isActive?: boolean }): TabLimitReason {
    const limitReason = this.checkLimits(tab.type);
    if (limitReason !== null) return limitReason;

    const activate = tab.isActive === true || this.tabs().length === 0;
    // One place for every open path (WS events, recovery, consultation): same display name.
    const named = { ...tab, customerName: normalizeContactName(tab.customerName) };
    this.tabs.update((current) =>
      activate
        ? [...current.map((t) => ({ ...t, isActive: false })), { ...named, isActive: true }]
        : [...current, { ...named, isActive: false }],
    );
    return null;
  }

  closeTab(id: string): void {
    // Single exit point for every tab (close button, sent reply, disposition saved, call end,
    // contact vanished): drop its drafts and accepted flag so nothing leaks to a later contact.
    const closing = this.tabs().find((t) => t.id === id);
    if (closing) {
      this.drafts.clear(closing.contactId);
      if (closing.originalContactId) this.drafts.clear(closing.originalContactId);
    }
    this.tabs.update((current) => {
      const index = current.findIndex((t) => t.id === id);
      const filtered = current.filter((t) => t.id !== id);
      // If we closed the active tab, activate its neighbour (the next one, else the last)
      const hadActive = index >= 0 && current[index].isActive;
      if (hadActive && filtered.length > 0) {
        const next = Math.min(index, filtered.length - 1);
        return filtered.map((t, i) => ({ ...t, isActive: i === next }));
      }
      return filtered;
    });
  }

  // ── Release (return to queue) in flight ────────────────────────────────────
  // While POST /release is pending the backend may re-assign the SAME contact to this agent. The
  // assignment event would be deduplicated against the still-existing tab, and the release
  // success would then remove the tab: the contact would be assigned but have no tab.

  private readonly releasing = new Map<string, ContactAssignedPayload | null>();

  beginRelease(contactId: string): void {
    this.releasing.set(contactId, null);
  }

  /** True (and the payload is kept) when the contact is being released: do not open a tab now. */
  noteAssignedDuringRelease(payload: ContactAssignedPayload): boolean {
    if (!this.releasing.has(payload.contactId)) return false;
    this.releasing.set(payload.contactId, payload);
    return true;
  }

  /** Ends the release; returns the assignment that arrived meanwhile, if any. */
  endRelease(contactId: string): ContactAssignedPayload | null {
    const reassigned = this.releasing.get(contactId) ?? null;
    this.releasing.delete(contactId);
    return reassigned;
  }

  /** Closes the tab of a contact (no-op when it is already gone). Used outside components. */
  closeByContactId(contactId: string): void {
    const tab = this.tabs().find((t) => t.contactId === contactId);
    if (tab) this.closeTab(tab.id);
  }

  /** Drops every tab, e.g. when the agent session ends. Drafts are cleared separately. */
  reset(): void {
    this.tabs.set([]);
    this.acwFocusTabId.set(null);
    this.calendarOpen.set(false);
  }

  setActiveTab(id: string): void {
    this.tabs.update((current) => current.map((t) => ({ ...t, isActive: t.id === id })));
  }

  updateTabStatus(id: string, status: ContactTabStatus): void {
    this.tabs.update((current) =>
      current.map((t) => {
        if (t.id !== id) return t;
        // Stamp the start of ACW on entering it (kept while it lasts, so the timer survives
        // remounts of the form) and drop it on leaving, so a later ACW starts from zero.
        const wrappingSince =
          status === 'WRAPPING'
            ? t.status === 'WRAPPING'
              ? (t.wrappingSince ?? Date.now())
              : Date.now()
            : undefined;
        return { ...t, status, wrappingSince };
      }),
    );
  }

  // ── ACW focus request ──────────────────────────────────────────────────────
  // Focus moves into the disposition form only on an explicit agent action (activating the ACW
  // tab, the "complete the disposition" banner), never on a render. The request outlives the
  // form because it may be made before the form is mounted (banner -> navigation -> mount).

  private readonly acwFocusTabId = signal<string | null>(null);

  requestAcwFocus(tabId: string): void {
    this.acwFocusTabId.set(tabId);
  }

  /** True once (and clears the request) when focus was requested for this tab. */
  consumeAcwFocus(tabId: string): boolean {
    if (this.acwFocusTabId() !== tabId) return false;
    this.acwFocusTabId.set(null);
    return true;
  }

  /** Tabs waiting for their disposition, longest-waiting first (ACW of a call that has ended). */
  readonly acwTabs = computed<ContactTab[]>(() =>
    this.tabs()
      .filter((t) => t.type === 'PHONE' && t.status === 'WRAPPING')
      .sort((a, b) => (a.wrappingSince ?? 0) - (b.wrappingSince ?? 0)),
  );

  /**
   * The desktop shows the calendar instead of the contact tabs. Shared (not desktop-local) so the
   * ACW banner knows that a WRAPPING tab which is "active" is in fact hidden behind the calendar.
   */
  readonly calendarOpen = signal(false);

  /**
   * Puts the tab into WRAPPING state (After Contact Work).
   * The DispositionPanelComponent will be shown for this tab.
   */
  markAsWrapping(id: string): void {
    this.updateTabStatus(id, 'WRAPPING');
  }

  updateTabCustomerInfo(
    contactId: string,
    customerName: string,
    direction?: CallDirection,
    customerId?: string,
  ): void {
    this.tabs.update((current) =>
      current.map((t) => {
        if (t.contactId !== contactId) return t;
        return {
          ...t,
          customerName: normalizeContactName(customerName),
          ...(direction !== undefined ? { direction } : {}),
          ...(customerId !== undefined ? { customerId } : {}),
        };
      }),
    );
  }

  /**
   * Updates the contactId of a tab (used when attended transfer bridge completes).
   * Clears originalContactId since the new contactId is already the proper UUID.
   * Optionally updates customerName when the consultation prefix must be removed.
   */
  updateTabContactId(id: string, newContactId: string, customerName?: string): void {
    const previous = this.tabs().find((t) => t.id === id);
    if (previous) this.drafts.rekey(previous.contactId, newContactId);
    this.tabs.update((current) =>
      current.map((t) => {
        if (t.id !== id) return t;
        return {
          ...t,
          contactId: newContactId,
          originalContactId: undefined,
          ...(customerName !== undefined ? { customerName } : {}),
        };
      }),
    );
  }

  private checkLimits(type: ContactType): TabLimitReason {
    if (this.totalTabCount() >= MAX_TOTAL_TABS) return 'MAX_TOTAL';
    if (type === 'PHONE' && this.phoneTabCount() >= MAX_PHONE_TABS) return 'MAX_PHONE';
    if ((type === 'CHAT' || type === 'EMAIL') && this.asyncTabCount() >= MAX_ASYNC_TABS) {
      return 'MAX_ASYNC';
    }
    return null;
  }
}
