import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { catchError, EMPTY, finalize } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { EmailService, EmailTemplate, PendingAttachment } from '../../../services/email.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { CustomerSummary } from '../../../models/customer-search.model';
import { ResizableDialogDirective } from '../../../../../shared/directives/resizable-dialog.directive';

@Component({
  selector: 'app-adhoc-email-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, ReactiveFormsModule, ResizableDialogDirective],
  templateUrl: './adhoc-email-modal.component.html',
  styleUrl: './adhoc-email-modal.component.scss',
  host: {
    '(document:keydown.escape)': 'onEscapeKey($event)',
  },
})
export class AdHocEmailModalComponent implements AfterViewInit {
  readonly customer = input.required<CustomerSummary>();

  readonly sent = output<void>();
  readonly cancelled = output<void>();

  private readonly fb = inject(FormBuilder);
  private readonly emailService = inject(EmailService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly translocoService = inject(TranslocoService);

  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('dialogEl');
  readonly fileInputRef = viewChild<ElementRef<HTMLInputElement>>('fileInput');
  readonly editorRef = viewChild<ElementRef<HTMLDivElement>>('editor');

  readonly loading = signal(false);
  readonly visible = signal(false);
  readonly errorMessage = signal<string | null>(null);

  readonly pendingAttachments = signal<PendingAttachment[]>([]);
  readonly uploading = signal(false);
  readonly uploadError = signal<string | null>(null);

  readonly templates = signal<EmailTemplate[]>([]);
  readonly templatesLoading = signal(false);
  readonly selectedTemplate = signal<EmailTemplate | null>(null);
  readonly templateVariables = signal<Record<string, string>>({});

  readonly bodyHtml = signal<string>('');

  readonly hasMultipleEmails = computed(() => this.customer().email.length > 1);

  readonly selectedTemplateHasVariables = computed(
    () => (this.selectedTemplate()?.variables.length ?? 0) > 0,
  );

  readonly customerDisplayName = computed(() => {
    const c = this.customer();
    return [c.firstName, c.lastName].filter(Boolean).join(' ') || 'Klient';
  });

  readonly bodyHtmlTouched = signal(false);

  readonly form = this.fb.group({
    templateId: [null as string | null],
    toAddress: ['', [Validators.required, Validators.email]],
    subject: ['', [Validators.required, Validators.maxLength(500)]],
  });

  get toAddressError(): string | null {
    const ctrl = this.form.controls.toAddress;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required'))
      return this.translocoService.translate('agent.adhocEmail.toRequired');
    if (ctrl.hasError('email'))
      return this.translocoService.translate('agent.adhocEmail.toInvalid');
    return null;
  }

  get subjectError(): string | null {
    const ctrl = this.form.controls.subject;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required'))
      return this.translocoService.translate('agent.adhocEmail.subjectRequired');
    if (ctrl.hasError('maxlength'))
      return this.translocoService.translate('agent.adhocEmail.subjectMaxLength');
    return null;
  }

  get bodyHtmlError(): string | null {
    if (!this.bodyHtmlTouched() || this.bodyHtml().trim().length > 0) return null;
    return this.translocoService.translate('agent.adhocEmail.bodyRequired');
  }

  get isSubmitDisabled(): boolean {
    return (
      this.form.invalid ||
      this.form.pending ||
      this.loading() ||
      this.uploading() ||
      this.bodyHtml().trim().length === 0
    );
  }

  ngAfterViewInit(): void {
    const emails = this.customer().email;
    if (emails.length > 0) {
      this.form.controls.toAddress.patchValue(emails[0]);
    }
    this.loadTemplates();
    this.open();
  }

  private loadTemplates(): void {
    if (this.templates().length > 0) return;
    this.templatesLoading.set(true);
    this.emailService
      .getTemplates(0, 100)
      .pipe(
        catchError(() => {
          this.templatesLoading.set(false);
          return EMPTY;
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((page) => {
        this.templates.set(page.content);
        this.templatesLoading.set(false);
      });
  }

  onTemplateChange(templateId: string | null): void {
    if (!templateId) {
      this.selectedTemplate.set(null);
      this.templateVariables.set({});
      this.form.controls.subject.patchValue('');
      this.setEditorHtml('');
      return;
    }

    const template = this.templates().find((t) => t.id === templateId) ?? null;
    this.selectedTemplate.set(template);

    if (!template) return;

    const emptyVars: Record<string, string> = {};
    for (const v of template.variables) {
      emptyVars[v] = '';
    }
    this.templateVariables.set(emptyVars);

    if (template.variables.length === 0) {
      this.applyTemplate();
    }
  }

  onVariableInput(variable: string, value: string): void {
    this.templateVariables.update((vars) => ({ ...vars, [variable]: value }));
  }

  applyTemplate(): void {
    const template = this.selectedTemplate();
    if (!template) return;

    this.errorMessage.set(null);
    this.templatesLoading.set(true);

    this.emailService
      .previewTemplate(template.id, this.templateVariables(), undefined, this.customer().customerId)
      .pipe(
        catchError(() => {
          this.templatesLoading.set(false);
          this.errorMessage.set(
            this.translocoService.translate('agent.adhocEmail.errorTemplateApply'),
          );
          return EMPTY;
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((preview) => {
        this.templatesLoading.set(false);
        this.form.controls.subject.patchValue(preview.subject);
        this.setEditorHtml(preview.bodyHtml);
      });
  }

  templateVariableKeys(): string[] {
    return Object.keys(this.templateVariables());
  }

  private setEditorHtml(html: string): void {
    const editor = this.editorRef()?.nativeElement;
    if (editor) {
      editor.innerHTML = html;
    }
    this.bodyHtml.set(html);
  }

  onEditorInput(event: Event): void {
    const div = event.target as HTMLDivElement;
    this.bodyHtml.set(div.innerHTML);
  }

  onEditorBlur(): void {
    this.bodyHtmlTouched.set(true);
  }

  execCommand(command: string): void {
    document.execCommand(command, false);
  }

  insertLink(): void {
    const url = window.prompt('Podaj adres URL:');
    if (url) {
      document.execCommand('createLink', false, url);
    }
  }

  triggerFileInput(): void {
    this.fileInputRef()?.nativeElement.click();
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    if (files.length === 0) return;

    this.uploadError.set(null);
    this.uploading.set(true);

    let remaining = files.length;

    for (const file of files) {
      this.emailService
        .uploadAttachment(file)
        .pipe(
          finalize(() => {
            remaining--;
            if (remaining === 0) {
              this.uploading.set(false);
            }
          }),
          catchError(() => {
            this.uploadError.set('Nie udało się przesłać pliku: ' + file.name);
            return EMPTY;
          }),
          takeUntilDestroyed(this.destroyRef),
        )
        .subscribe((uploaded) => {
          this.pendingAttachments.update((list) => [
            ...list,
            {
              s3Key: uploaded.s3Key,
              filename: uploaded.filename,
              contentType: uploaded.contentType,
              sizeBytes: uploaded.sizeBytes,
            },
          ]);
        });
    }

    // Reset input so the same file can be selected again
    input.value = '';
  }

  removeAttachment(s3Key: string): void {
    this.pendingAttachments.update((list) => list.filter((a) => a.s3Key !== s3Key));
  }

  trackByS3Key(_index: number, att: PendingAttachment): string {
    return att.s3Key;
  }

  formatFileSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }

  open(): void {
    this.visible.set(true);
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog && !dialog.open) dialog.showModal();
  }

  close(): void {
    this.visible.set(false);
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog && dialog.open) dialog.close();
  }

  onEscapeKey(event: Event): void {
    if (this.visible()) {
      event.preventDefault();
      this.close();
      this.cancelled.emit();
    }
  }

  onBackdropClick(event: MouseEvent): void {
    const dialog = this.dialogRef()?.nativeElement;
    if (event.target === dialog) {
      this.close();
      this.cancelled.emit();
    }
  }

  protected cancel(): void {
    this.close();
    this.cancelled.emit();
  }

  protected submit(): void {
    this.form.markAllAsTouched();
    this.bodyHtmlTouched.set(true);
    if (this.isSubmitDisabled) return;

    this.errorMessage.set(null);
    this.loading.set(true);

    const raw = this.form.getRawValue();

    this.emailService
      .sendOutbound({
        toAddress: raw.toAddress!.trim(),
        subject: raw.subject!.trim(),
        bodyHtml: this.bodyHtml(),
        customerId: this.customer().customerId,
        attachments: this.pendingAttachments(),
      })
      .pipe(
        catchError((err: HttpErrorResponse) => {
          this.loading.set(false);
          if (err.status === 503 || err.error?.code === 'SMTP_NOT_CONFIGURED') {
            this.errorMessage.set(this.translocoService.translate('agent.adhocEmail.errorNoSmtp'));
          } else {
            this.errorMessage.set(this.translocoService.translate('agent.adhocEmail.errorSend'));
          }
          return EMPTY;
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        this.loading.set(false);
        this.pendingAttachments.set([]);
        this.notifications.success(this.translocoService.translate('agent.adhocEmail.sent'));
        this.close();
        this.sent.emit();
      });
  }
}
