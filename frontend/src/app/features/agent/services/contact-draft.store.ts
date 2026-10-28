import { Injectable, computed, signal } from '@angular/core';

import { EmailTemplate, PendingAttachment } from './email.service';

/**
 * In-memory email reply draft. Attachments are references to files already uploaded
 * (s3Key), never file contents.
 */
export interface EmailDraft {
  bodyHtml: string;
  subject: string;
  /** Subject the component derived itself ("Re: ..."); not user input, so it is not a draft. */
  subjectEdited: boolean;
  template: EmailTemplate | null;
  templateVariables: Record<string, string>;
  showVariableForm: boolean;
  attachments: PendingAttachment[];
}

/**
 * Per-contact drafts for the agent desktop (email reply, social reply, PHONE note) and the
 * "already accepted" flag, keyed by `contactId`.
 *
 * The desktop renders only the active tab, so contact components are destroyed on every tab
 * switch. State kept here outlives them.
 *
 * Privacy (RODO): customer message content lives ONLY in memory. Never persist it to
 * localStorage / sessionStorage / IndexedDB. A page reload intentionally loses drafts.
 */
@Injectable({ providedIn: 'root' })
export class ContactDraftStore {
  private readonly emailDrafts = signal<Readonly<Record<string, EmailDraft>>>({});
  private readonly socialReplies = signal<Readonly<Record<string, string>>>({});
  private readonly phoneNotes = signal<Readonly<Record<string, string>>>({});
  private readonly acceptedIds = signal<ReadonlySet<string>>(new Set());
  private readonly sendingIds = signal<ReadonlySet<string>>(new Set());
  private readonly sendFailedIds = signal<ReadonlySet<string>>(new Set());

  /** contactIds that currently hold an unsent draft (for the "Draft" marker and close guards). */
  readonly draftContactIds = computed<ReadonlySet<string>>(() => {
    const ids = new Set<string>();
    for (const [id, d] of Object.entries(this.emailDrafts())) {
      if (isEmailDraftMeaningful(d)) ids.add(id);
    }
    for (const id of Object.keys(this.socialReplies())) ids.add(id);
    for (const id of Object.keys(this.phoneNotes())) ids.add(id);
    return ids;
  });

  /** True when there is an unsent draft for the contact. Reactive when read in computed/template. */
  hasUnsentDraft(contactId: string): boolean {
    return this.draftContactIds().has(contactId);
  }

  // ── Email ──────────────────────────────────────────────────────────────────

  emailDraft(contactId: string): EmailDraft | null {
    return this.emailDrafts()[contactId] ?? null;
  }

  setEmailDraft(contactId: string, draft: EmailDraft): void {
    if (!isEmailDraftMeaningful(draft) && !draft.subjectEdited && draft.template === null) {
      this.clearEmailDraft(contactId);
      return;
    }
    this.emailDrafts.update((cur) => ({ ...cur, [contactId]: draft }));
  }

  clearEmailDraft(contactId: string): void {
    this.emailDrafts.update((cur) => omit(cur, contactId));
  }

  // ── Social ─────────────────────────────────────────────────────────────────

  socialReply(contactId: string): string {
    return this.socialReplies()[contactId] ?? '';
  }

  setSocialReply(contactId: string, text: string): void {
    this.socialReplies.update((cur) =>
      text.trim().length === 0 ? omit(cur, contactId) : { ...cur, [contactId]: text },
    );
  }

  // ── PHONE note ─────────────────────────────────────────────────────────────

  phoneNote(contactId: string): string {
    return this.phoneNotes()[contactId] ?? '';
  }

  setPhoneNote(contactId: string, note: string): void {
    this.phoneNotes.update((cur) =>
      note.trim().length === 0 ? omit(cur, contactId) : { ...cur, [contactId]: note },
    );
  }

  // ── acceptContact guard ────────────────────────────────────────────────────

  /**
   * Marks the contact as accepted. Returns true only for the first call, so the caller sends
   * `acceptContact` exactly once per contact no matter how many times the card is re-mounted.
   */
  markAccepted(contactId: string): boolean {
    if (this.acceptedIds().has(contactId)) return false;
    this.acceptedIds.update((cur) => new Set(cur).add(contactId));
    return true;
  }

  /** Reverts markAccepted after a failed request so the next mount retries. */
  unmarkAccepted(contactId: string): void {
    this.acceptedIds.update((cur) => {
      const next = new Set(cur);
      next.delete(contactId);
      return next;
    });
  }

  // ── Email send in flight ───────────────────────────────────────────────────
  // Lives here (not in the component) because the card is unmounted on a tab switch while the
  // request keeps running; after coming back the send button must stay blocked.

  isSending(contactId: string): boolean {
    return this.sendingIds().has(contactId);
  }

  /** Returns false when a send for this contact is already running. */
  markSending(contactId: string): boolean {
    if (this.sendingIds().has(contactId)) return false;
    this.sendingIds.update((cur) => new Set(cur).add(contactId));
    this.sendFailedIds.update((cur) => withoutId(cur, contactId));
    return true;
  }

  unmarkSending(contactId: string): void {
    this.sendingIds.update((cur) => withoutId(cur, contactId));
  }

  /** The last send failed while nobody may have been looking; the card shows it once. */
  markSendFailed(contactId: string): void {
    this.sendFailedIds.update((cur) => new Set(cur).add(contactId));
  }

  hasSendFailed(contactId: string): boolean {
    return this.sendFailedIds().has(contactId);
  }

  clearSendFailed(contactId: string): void {
    this.sendFailedIds.update((cur) => withoutId(cur, contactId));
  }

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  /** Drops every draft and the accepted flag of a contact (sent, closed, returned to queue). */
  clear(contactId: string): void {
    this.clearEmailDraft(contactId);
    this.socialReplies.update((cur) => omit(cur, contactId));
    this.phoneNotes.update((cur) => omit(cur, contactId));
    this.unmarkAccepted(contactId);
  }

  /**
   * Re-keys drafts when a tab's contactId changes (attended transfer bridge).
   * Never overwrites what already exists under the new key: notes are joined (target first),
   * for email / social drafts the target's own draft wins.
   */
  rekey(oldContactId: string, newContactId: string): void {
    if (oldContactId === newContactId) return;
    const email = this.emailDrafts()[oldContactId];
    const social = this.socialReplies()[oldContactId];
    const note = this.phoneNotes()[oldContactId];
    const wasAccepted = this.acceptedIds().has(oldContactId);
    this.clear(oldContactId);
    if (email && !this.emailDrafts()[newContactId]) this.setEmailDraft(newContactId, email);
    if (social !== undefined && !this.socialReplies()[newContactId]) {
      this.setSocialReply(newContactId, social);
    }
    if (note !== undefined) {
      const existing = this.phoneNotes()[newContactId];
      this.setPhoneNote(newContactId, existing ? `${existing}\n${note}` : note);
    }
    if (wasAccepted) this.markAccepted(newContactId);
  }

  /** Logout / leaving the agent area: nothing of a previous agent may linger in memory. */
  clearAll(): void {
    this.emailDrafts.set({});
    this.socialReplies.set({});
    this.phoneNotes.set({});
    this.acceptedIds.set(new Set());
    this.sendingIds.set(new Set());
    this.sendFailedIds.set(new Set());
  }
}

function withoutId(set: ReadonlySet<string>, id: string): ReadonlySet<string> {
  if (!set.has(id)) return set;
  const next = new Set(set);
  next.delete(id);
  return next;
}

function isEmailDraftMeaningful(d: EmailDraft): boolean {
  return htmlHasContent(d.bodyHtml) || d.attachments.length > 0;
}

/** Empty contenteditable leaves `<br>` / `<div><br></div>`; those are not content. */
function htmlHasContent(html: string): boolean {
  return html.replace(/<br\s*\/?>|<\/?div>|&nbsp;|\s/gi, '').length > 0;
}

function omit<T>(rec: Readonly<Record<string, T>>, key: string): Readonly<Record<string, T>> {
  if (!(key in rec)) return rec;
  const rest = { ...rec };
  delete rest[key];
  return rest;
}
