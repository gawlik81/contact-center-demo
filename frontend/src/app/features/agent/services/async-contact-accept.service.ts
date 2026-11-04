import { Injectable, inject } from '@angular/core';
import { catchError, mergeMap, of, retry, throwError, timer } from 'rxjs';
import { ContactType } from '../models/contact-tab.model';
import { ContactDraftStore } from './contact-draft.store';
import { ContactService } from './contact.service';

/** Channels whose card must confirm the contact (everything except PHONE, which rings). */
export function isAsyncAcceptable(type: ContactType): boolean {
  return type !== 'PHONE';
}

/** Retries after a network error or a 5xx; the delay grows with the attempt (1 s, 2 s). */
export const ACCEPT_RETRY_COUNT = 2;
export const ACCEPT_RETRY_BASE_MS = 1000;

/** Marker: the retry loop stopped because its mark no longer exists (not an HTTP error). */
const ABANDONED = Symbol('accept-retry-abandoned');

function statusOf(err: unknown): number {
  return err && typeof err === 'object' && 'status' in err
    ? Number((err as { status: unknown }).status)
    : 0;
}

/** Status 0 (network) and 5xx may succeed later; any 4xx is a final answer. */
function isTransient(err: unknown): boolean {
  const status = statusOf(err);
  return status === 0 || status >= 500;
}

/**
 * Single place that sends `POST /api/contacts/{id}/accept` for asynchronous cards (EMAIL, SOCIAL,
 * CHAT).
 *
 * The backend moves the contact ASSIGNED -> ACTIVE, which stops ContactAssignmentMonitor from
 * re-sending CONTACT_ASSIGNED and finally re-queueing a contact the agent is already handling.
 *
 * Triggers, all through ContactDraftStore.markAccepted, so the request is sent once per contact
 * however many of them fire: a tab opened from CONTACT_ASSIGNED (live path, re-delivery, re-open
 * after a release), a card mount (safety net for a tab opened by any other path) and the
 * page-reload recovery.
 *
 * Failure handling: the request is silent (no global toast; the card is fully usable meanwhile).
 * A network error or 5xx is retried twice with a short backoff; if it still fails the mark is
 * reverted, so the next trigger (the monitor re-delivers CONTACT_ASSIGNED, a card mount, a
 * recovery run) sends it again. A 4xx is final (contact closed, reassigned, not ours): the mark is
 * kept so nothing loops, and a warning is logged. A pending retry is dropped (and never reverts or
 * logs anything) once its mark is gone or replaced: release, logout and a new assignment of the
 * same contact each change ContactDraftStore.acceptEpoch.
 */
@Injectable({ providedIn: 'root' })
export class AsyncContactAcceptService {
  private readonly contacts = inject(ContactService);
  private readonly drafts = inject(ContactDraftStore);

  /** Accepts a freshly opened tab if its channel needs it. */
  acceptOpened(type: ContactType, contactId: string): void {
    if (isAsyncAcceptable(type)) this.accept(contactId);
  }

  /**
   * Fire-and-forget and deliberately not tied to a component lifecycle, so a quick tab switch
   * never cancels a request the store counts as sent.
   */
  accept(contactId: string): void {
    if (!this.drafts.markAccepted(contactId)) return;
    // "This mark": a retry or the final revert only acts while it is still the same mark.
    const epoch = this.drafts.acceptEpoch(contactId);
    const current = () =>
      this.drafts.isAccepted(contactId) && this.drafts.acceptEpoch(contactId) === epoch;
    this.contacts
      .acceptContact(contactId, { silent: true })
      .pipe(
        retry({
          count: ACCEPT_RETRY_COUNT,
          delay: (err, attempt) =>
            isTransient(err)
              ? timer(ACCEPT_RETRY_BASE_MS * attempt).pipe(
                  // Released / logged out / assigned again while waiting: stop, send nothing.
                  mergeMap(() => (current() ? of(0) : throwError(() => ABANDONED))),
                )
              : throwError(() => err),
        }),
        catchError((err: unknown) => {
          if (err === ABANDONED || !current()) return of(null);
          if (isTransient(err)) {
            this.drafts.unmarkAccepted(contactId);
          } else {
            console.warn(
              `[AsyncContactAccept] accept refused (HTTP ${statusOf(err)}) for ${contactId}; not retrying`,
            );
          }
          return of(null);
        }),
      )
      .subscribe();
  }
}
