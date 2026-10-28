import { Injectable, inject } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';

import { NotificationService } from '../../../core/services/notification.service';
import { ContactDraftStore } from './contact-draft.store';
import { ContactTabStore } from './contact-tab.store';
import { EmailService, SendReplyRequest } from './email.service';

/**
 * Sends an email reply independently of the contact card's lifetime.
 *
 * The desktop renders only the active tab, so the card can be unmounted mid-request. A request
 * owned by the component would be cancelled client-side (the server may still process it) and the
 * agent could then send the same reply again. Here the request always runs to completion; on
 * success the draft is dropped, the tab closed and the agent notified, whether or not the card is
 * currently on screen.
 */
@Injectable({ providedIn: 'root' })
export class EmailReplyService {
  private readonly emailService = inject(EmailService);
  private readonly drafts = inject(ContactDraftStore);
  private readonly tabs = inject(ContactTabStore);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);

  /** No-op when a send for this contact is already in flight. */
  send(contactId: string, messageId: string, request: SendReplyRequest): void {
    if (!this.drafts.markSending(contactId)) return;

    this.emailService.sendReply(messageId, request).subscribe({
      next: () => {
        this.drafts.unmarkSending(contactId);
        this.drafts.clear(contactId);
        this.tabs.closeByContactId(contactId);
        this.notifications.success(this.transloco.translate('agent.desktop.replySent'));
      },
      error: () => {
        this.drafts.unmarkSending(contactId);
        this.drafts.markSendFailed(contactId);
      },
    });
  }
}
