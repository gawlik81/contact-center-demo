import { Injectable, Injector, inject } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { NotificationService } from '../../../core/services/notification.service';
import { AgentRecoveryService, ReconcileOutcome } from './agent-recovery.service';

/** True for HTTP 409 (ConflictException: a race with a parallel change of status or agent). */
export function isConflict(err: unknown): boolean {
  return !!err && typeof err === 'object' && (err as { status?: unknown }).status === 409;
}

/**
 * One reaction to "the contact was changed meanwhile" (HTTP 409 from updating a contact, saving
 * its disposition or abandoning it; BE-209). It reads the contact's real state from the backend
 * (AgentRecoveryService.reconcileContact) and says what it found:
 * - no longer the agent's (closed, finished, handed over): its card is closed, the typed text is
 *   put on the clipboard, so the same 409 cannot repeat on a card that can never succeed;
 * - still the agent's: the card is updated, and the caller's form and drafts stay, so the action
 *   can be repeated;
 * - could not be checked: the message says so and nothing changes.
 */
@Injectable({ providedIn: 'root' })
export class ContactConflictService {
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly injector = inject(Injector);

  /**
   * `stillYoursKey` is the caller's full, translatable sentence for "changed, but still yours".
   * Resolves with what was found; never rejects.
   */
  async handle(stillYoursKey: string, contactId: string): Promise<ReconcileOutcome['verdict']> {
    // Lazy: the recovery service pulls in the softphone.
    const outcome = await this.injector
      .get(AgentRecoveryService)
      .reconcileContact(contactId)
      .catch((): ReconcileOutcome => ({ verdict: 'unknown', closed: false, notesCopied: false }));
    let key: string;
    if (outcome.verdict === 'gone') {
      key = outcome.notesCopied
        ? 'agent.contactConflict.goneNotesCopied'
        : 'agent.contactConflict.gone';
    } else if (outcome.verdict === 'mine') {
      key = stillYoursKey;
    } else {
      key = 'agent.contactConflict.unverified';
    }
    this.notifications.warning(this.transloco.translate(key));
    return outcome.verdict;
  }
}
