import { TranslocoModule } from '@jsverse/transloco';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  ViewChild,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { catchError, debounceTime, of, startWith } from 'rxjs';

import {
  EmailService,
  EmailMessage,
  EmailTemplate,
  PendingAttachment,
  SendReplyRequest,
} from '../../../services/email.service';
import { ContactService } from '../../../services/contact.service';
import { EmailThreadMessageComponent } from './email-thread-message/email-thread-message.component';
import { AiSummaryPanelComponent } from '../../../../../shared/components/ai-summary-panel/ai-summary-panel.component';

@Component({
  selector: 'cc-email-contact',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslocoModule,
    ReactiveFormsModule,
    EmailThreadMessageComponent,
    AiSummaryPanelComponent,
  ],
  templateUrl: './email-contact.component.html',
  styleUrl: './email-contact.component.scss',
})
export class EmailContactComponent implements OnInit {
  contactId = input.required<string>();
  replySent = output<boolean>();

  @ViewChild('editor') private editorRef?: ElementRef<HTMLDivElement>;
  @ViewChild('fileInput') private fileInputRef?: ElementRef<HTMLInputElement>;

  private readonly emailService = inject(EmailService);
  private readonly contactService = inject(ContactService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly loading = signal(true);
  protected readonly loadingMore = signal(false);
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly threadCollapsed = signal(false);

  protected readonly rootMessage = signal<EmailMessage | null>(null);
  protected readonly thread = signal<EmailMessage[]>([]);
  protected readonly hasMore = signal(false);
  protected readonly currentPage = signal(0);

  protected readonly templates = signal<EmailTemplate[]>([]);
  protected readonly selectedTemplate = signal<EmailTemplate | null>(null);
  protected readonly templateVariables = signal<Record<string, string>>({});
  protected readonly showVariableForm = signal(false);

  protected readonly replyHtml = signal<string>('');
  protected readonly replySubject = signal<string>('');

  // ===== Attachment upload state =====
  protected readonly pendingAttachments = signal<PendingAttachment[]>([]);
  protected readonly uploading = signal(false);
  protected readonly uploadError = signal<string | null>(null);

  protected readonly templateSearchControl = new FormControl<string>('');
  protected readonly filteredTemplates = signal<EmailTemplate[]>([]);

  protected readonly hasTemplateVariables = computed(() => {
    const tpl = this.selectedTemplate();
    return tpl !== null && tpl.variables.length > 0;
  });

  protected readonly canSend = computed(() => {
    const html = this.replyHtml();
    return html.trim().length > 0 && !this.sending();
  });

  ngOnInit(): void {
    this.loadMessage();
    this.loadTemplates();

    // Notify backend that the agent opened this contact (ASSIGNED → ACTIVE).
    // This stops ContactAssignmentMonitor from re-queueing the contact.
    // Fire-and-forget — idempotent, failure is non-critical.
    this.contactService
      .acceptContact(this.contactId())
      .pipe(
        catchError(() => of(null)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe();

    this.templateSearchControl.valueChanges
      .pipe(startWith(''), debounceTime(150), takeUntilDestroyed(this.destroyRef))
      .subscribe((value) => {
        const search = value ?? '';
        const all = this.templates();
        this.filteredTemplates.set(
          search ? all.filter((t) => t.name.toLowerCase().includes(search.toLowerCase())) : all,
        );
      });
  }

  private loadMessage(): void {
    this.loading.set(true);
    this.error.set(null);

    this.emailService
      .getMessage(this.contactId())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (msg) => {
          this.rootMessage.set(msg);
          this.replySubject.set(msg.subject.startsWith('Re:') ? msg.subject : `Re: ${msg.subject}`);
          this.loadThread(msg.threadRootMessageId ?? msg.messageIdHeader ?? msg.id, 0, true);
        },
        error: () => {
          this.error.set('Nie udalo sie zaladowac wiadomosci email.');
          this.loading.set(false);
        },
      });
  }

  private loadThread(threadRootMessageId: string, page: number, initial: boolean): void {
    if (!initial) {
      this.loadingMore.set(true);
    }

    this.emailService
      .getThread(threadRootMessageId, page, 20)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          if (initial) {
            this.thread.set(
              response.content.length > 0
                ? response.content
                : ([this.rootMessage()].filter(Boolean) as EmailMessage[]),
            );
          } else {
            this.thread.update((current) => [...response.content, ...current]);
          }
          this.hasMore.set(!response.first);
          this.currentPage.set(page);
          this.loading.set(false);
          this.loadingMore.set(false);
        },
        error: () => {
          this.error.set('Nie udalo sie zaladowac watku email.');
          this.loading.set(false);
          this.loadingMore.set(false);
        },
      });
  }

  private loadTemplates(): void {
    this.emailService
      .getTemplates(0, 100)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.templates.set(response.content);
          this.filteredTemplates.set(response.content);
        },
        error: () => {
          // non-critical – templates are optional
        },
      });
  }

  protected loadMoreMessages(): void {
    const root = this.rootMessage();
    if (!root || this.loadingMore()) return;
    this.loadThread(root.threadRootMessageId, this.currentPage() + 1, false);
  }

  protected onTemplateSelected(template: EmailTemplate): void {
    this.selectedTemplate.set(template);
    this.templateSearchControl.setValue(template.name, { emitEvent: false });
    this.filteredTemplates.set([]);

    if (template.variables.length > 0) {
      const vars: Record<string, string> = {};
      template.variables.forEach((v) => (vars[v] = ''));
      this.templateVariables.set(vars);
      this.showVariableForm.set(true);
    } else {
      this.applyTemplateViaApi(template, {});
    }
  }

  protected onVariableChange(variable: string, value: string): void {
    this.templateVariables.update((current) => ({ ...current, [variable]: value }));
  }

  protected previewTemplate(): void {
    const tpl = this.selectedTemplate();
    if (!tpl) return;
    this.applyTemplateViaApi(tpl, this.templateVariables());
  }

  private applyTemplateViaApi(template: EmailTemplate, variables: Record<string, string>): void {
    this.emailService
      .previewTemplate(template.id, variables, this.contactId())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (preview) => {
          this.setEditorHtml(preview.bodyHtml);
          this.replySubject.set(preview.subject);
          this.showVariableForm.set(false);
        },
        error: () => {
          this.error.set('Nie udało się wczytać szablonu.');
        },
      });
  }

  private setEditorHtml(html: string): void {
    if (this.editorRef) {
      this.editorRef.nativeElement.innerHTML = html;
    }
    this.replyHtml.set(html);
  }

  protected onEditorInput(event: Event): void {
    const div = event.target as HTMLDivElement;
    this.replyHtml.set(div.innerHTML);
  }

  protected execCommand(command: string): void {
    document.execCommand(command, false);
  }

  protected insertLink(): void {
    const url = window.prompt('Podaj adres URL:');
    if (url) {
      document.execCommand('createLink', false, url);
    }
  }

  // ===== Attachment methods =====

  protected triggerFileInput(): void {
    this.fileInputRef?.nativeElement.click();
  }

  protected onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    if (!files.length) return;
    input.value = ''; // reset so same file can be re-selected

    this.uploading.set(true);
    this.uploadError.set(null);

    const uploadNext = (index: number): void => {
      if (index >= files.length) {
        this.uploading.set(false);
        return;
      }
      const file = files[index];
      this.emailService
        .uploadAttachment(file)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (resp) => {
            this.pendingAttachments.update((list) => [
              ...list,
              {
                s3Key: resp.s3Key,
                filename: resp.filename,
                contentType: resp.contentType,
                sizeBytes: resp.sizeBytes,
              },
            ]);
            uploadNext(index + 1);
          },
          error: () => {
            this.uploading.set(false);
            this.uploadError.set('Nie udało się przesłać pliku: ' + file.name);
          },
        });
    };

    uploadNext(0);
  }

  protected removeAttachment(s3Key: string): void {
    this.pendingAttachments.update((list) => list.filter((a) => a.s3Key !== s3Key));
  }

  protected formatFileSize(bytes: number): string {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  }

  // ===== Send / Cancel =====

  protected sendReply(): void {
    const root = this.rootMessage();
    if (!root || !this.canSend()) return;

    this.sending.set(true);
    this.error.set(null);

    const request: SendReplyRequest = {
      bodyHtml: this.replyHtml(),
      subject: this.replySubject(),
      attachments: this.pendingAttachments().length > 0 ? this.pendingAttachments() : undefined,
    };

    const tpl = this.selectedTemplate();
    if (tpl) {
      request.templateId = tpl.id;
      const vars = this.templateVariables();
      if (Object.keys(vars).length > 0) {
        request.templateVariables = vars;
      }
    }

    this.emailService
      .sendReply(root.id, request)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.sending.set(false);
          this.pendingAttachments.set([]);
          this.replySent.emit(true);
        },
        error: () => {
          this.sending.set(false);
          this.error.set('Nie udalo sie wyslac odpowiedzi. Sprobuj ponownie.');
        },
      });
  }

  protected cancelReply(): void {
    // Abandon the contact on the backend before closing the tab.
    // Fire-and-forget — idempotent, UI closes regardless of backend response.
    this.contactService
      .abandonContact(this.contactId())
      .pipe(
        catchError(() => of(null)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe();

    this.replySent.emit(false);
  }

  protected getVariableKeys(): string[] {
    return Object.keys(this.templateVariables());
  }

  protected trackByMessageId(_index: number, msg: EmailMessage): string {
    return msg.id;
  }

  protected trackByTemplateId(_index: number, tpl: EmailTemplate): string {
    return tpl.id;
  }

  protected trackByKey(_index: number, key: string): string {
    return key;
  }

  protected trackByS3Key(_index: number, att: PendingAttachment): string {
    return att.s3Key;
  }
}
