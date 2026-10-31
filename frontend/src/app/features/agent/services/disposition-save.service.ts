import { Injectable, Injector, inject, signal } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { toObservable } from '@angular/core/rxjs-interop';
import { EMPTY, Subject, filter, finalize, of, switchMap, take } from 'rxjs';
import { AuthService } from '../../../core/services/auth.service';
import { NotificationService } from '../../../core/services/notification.service';
import { AgentStatusService } from './agent-status.service';
import { ContactService } from './contact.service';
import { ContactTabStore } from './contact-tab.store';

export interface DispositionSaveRequest {
  /** PHONE tab in ACW that is closed once the disposition is saved. */
  tabId: string;
  /** Contact the disposition belongs to (`POST /contacts/{id}/disposition`). */
  contactId: string;
  code: string;
  notes: string;
}

/**
 * Saves the ACW disposition independently of the form's lifetime.
 *
 * The form is ordinary tab content now (FE-122): the agent may switch to another tab while the
 * request is in flight, which destroys the form. The toast, the return to AVAILABLE and the
 * closing of the ACW tab must still happen exactly once, so they live here and not in the
 * component. A remounted form reads `isSaving` from here and stays blocked until the end.
 */
@Injectable({ providedIn: 'root' })
export class DispositionSaveService {
  private readonly contacts = inject(ContactService);
  private readonly status = inject(AgentStatusService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly tabStore = inject(ContactTabStore);
  private readonly auth = inject(AuthService);
  private readonly injector = inject(Injector);

  private readonly savingIds = signal<ReadonlySet<string>>(new Set());

  /** Emits after the ACW tab was closed; carries the tab that took over (for focus repair). */
  readonly closed$ = new Subject<{ neighbourTabId: string | null }>();

  /** Reactive when read in computed/template. */
  isSaving(contactId: string): boolean {
    return this.savingIds().has(contactId);
  }

  save(req: DispositionSaveRequest): void {
    if (this.savingIds().has(req.contactId)) return;
    this.setSaving(req.contactId, true);

    this.contacts.setDisposition(req.contactId, req.code, req.notes).subscribe({
      error: () => {
        // The form and its draft stay as they are: the agent retries. The HTTP interceptor is
        // told to stay silent for this call, so this is the one and only toast.
        this.notifications.error(this.transloco.translate('agent.disposition.errorSave'));
        this.setSaving(req.contactId, false);
      },
      next: () => {
        this.notifications.success(this.transloco.translate('agent.disposition.successSave'));
        this.returnToAvailable(req);
      },
    });
  }

  /**
   * The disposition IS saved, so the tab closes whatever happens to the status change.
   * `AgentStatusService.changeStatus()` never errors: a failure shows its own toast and the
   * observable simply completes empty; it also returns EMPTY at once while another change (the
   * AFTER_CONTACT PATCH of the call that just ended) is in flight. Hence: wait for that one,
   * then change, and finish on completion, not on `next`.
   */
  private returnToAvailable(req: DispositionSaveRequest): void {
    const idle$ = this.status.isChanging()
      ? toObservable(this.status.isChanging, { injector: this.injector }).pipe(
          filter((changing) => !changing),
          take(1),
        )
      : of(false);
    idle$
      .pipe(
        switchMap(() => {
          // Logged out while the request was running: no session left to change the status of.
          if (!this.auth.isAuthenticated()) return EMPTY;
          return this.status.changeStatus('AVAILABLE');
        }),
        finalize(() => this.finish(req)),
      )
      .subscribe();
  }

  private finish(req: DispositionSaveRequest): void {
    const tabs = this.tabStore.tabs();
    const index = tabs.findIndex((t) => t.id === req.tabId);
    const neighbour = index >= 0 ? (tabs[index + 1] ?? tabs[index - 1] ?? null) : null;
    // Single cleanup point: drops the disposition draft and the PHONE note too.
    if (index >= 0) this.tabStore.closeTab(req.tabId);
    this.setSaving(req.contactId, false);
    this.closed$.next({ neighbourTabId: neighbour?.id ?? null });
  }

  private setSaving(contactId: string, on: boolean): void {
    this.savingIds.update((cur) => {
      const next = new Set(cur);
      if (on) next.add(contactId);
      else next.delete(contactId);
      return next;
    });
  }
}
