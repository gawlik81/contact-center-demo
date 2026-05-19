import { Injectable, computed, signal } from '@angular/core';
import {
  CallDirection,
  ContactTab,
  ContactTabStatus,
  ContactType,
} from '../models/contact-tab.model';
import { ContactAssignedPayload, CallIncomingPayload } from '../models/ws-event.model';

export const MAX_PHONE_TABS = 1;
export const MAX_ASYNC_TABS = 3;
export const MAX_TOTAL_TABS = 4;

export type TabLimitReason = 'MAX_PHONE' | 'MAX_ASYNC' | 'MAX_TOTAL' | null;

@Injectable({ providedIn: 'root' })
export class ContactTabStore {
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

  openTab(tab: Omit<ContactTab, 'isActive'> & { isActive?: boolean }): TabLimitReason {
    const limitReason = this.checkLimits(tab.type);
    if (limitReason !== null) return limitReason;

    const newTab: ContactTab = { ...tab, isActive: false };
    this.tabs.update((current) => {
      // Deactivate all, activate new one
      const deactivated = current.map((t) => ({ ...t, isActive: false }));
      return [...deactivated, { ...newTab, isActive: true }];
    });
    return null;
  }

  closeTab(id: string): void {
    this.tabs.update((current) => {
      const filtered = current.filter((t) => t.id !== id);
      // If we closed the active tab, activate the first remaining
      const hadActive = current.find((t) => t.id === id)?.isActive ?? false;
      if (hadActive && filtered.length > 0) {
        return filtered.map((t, i) => ({ ...t, isActive: i === 0 }));
      }
      return filtered;
    });
  }

  setActiveTab(id: string): void {
    this.tabs.update((current) => current.map((t) => ({ ...t, isActive: t.id === id })));
  }

  updateTabStatus(id: string, status: ContactTabStatus): void {
    this.tabs.update((current) => current.map((t) => (t.id === id ? { ...t, status } : t)));
  }

  /**
   * Puts the tab into WRAPPING state (After Contact Work).
   * The DispositionPanelComponent will be shown for this tab.
   */
  markAsWrapping(id: string): void {
    this.updateTabStatus(id, 'WRAPPING');
  }

  updateTabNote(id: string, note: string): void {
    this.tabs.update((current) => current.map((t) => (t.id === id ? { ...t, note } : t)));
  }

  /**
   * Updates the contactId of a tab (used when attended transfer bridge completes).
   * Clears originalContactId since the new contactId is already the proper UUID.
   * Optionally updates customerName when the consultation prefix must be removed.
   */
  updateTabContactId(id: string, newContactId: string, customerName?: string): void {
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
