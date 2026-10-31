import { Injectable, computed, inject } from '@angular/core';
import { Router } from '@angular/router';
import { currentUrlSignal } from '../../../shared/utils/router-url.util';
import { ContactTabStore } from './contact-tab.store';

/**
 * State shared by the two renderings of the ACW reminder (FE-122, FE-123): the floating card the
 * agent shell shows on every agent page except the desktop, and the inline chip in the desktop
 * toolbar. Both read the same `pendingTab`, so moving between places never changes what is shown.
 */
@Injectable({ providedIn: 'root' })
export class AcwReminderService {
  private readonly tabStore = inject(ContactTabStore);
  private readonly router = inject(Router);
  private readonly url = currentUrlSignal();

  /** True on the agent desktop, where the reminder lives in the toolbar (no layout cost). */
  readonly onDesktop = computed(() => {
    const path = this.url().split(/[?#]/)[0];
    return path === '/agent/desktop' || path.startsWith('/agent/desktop/');
  });

  /** The ACW tab waiting longest, unless the form is already in front of the agent. */
  readonly pendingTab = computed(() => {
    const tab = this.tabStore.acwTabs()[0];
    if (!tab) return null;
    const inFront = this.onDesktop() && tab.isActive && !this.tabStore.calendarOpen();
    return inFront ? null : tab;
  });

  /** Further ACW tabs besides the one shown (future-proof: today there is one PHONE tab). */
  readonly moreCount = computed(() => Math.max(0, this.tabStore.acwTabs().length - 1));

  /** Name for the reminder; falls back to the number when the lookup did not resolve a name. */
  readonly who = computed(() => {
    const tab = this.pendingTab();
    return tab ? tab.customerName || tab.customerIdentifier : '';
  });

  /** Brings the form in front: an explicit action of the agent, so it may close the calendar. */
  complete(): void {
    const tab = this.pendingTab();
    if (!tab) return;
    // The calendar view would hide the form: leave it.
    this.tabStore.calendarOpen.set(false);
    this.tabStore.setActiveTab(tab.id);
    this.tabStore.requestAcwFocus(tab.id);
    void this.router.navigate(['/agent/desktop']);
  }
}
