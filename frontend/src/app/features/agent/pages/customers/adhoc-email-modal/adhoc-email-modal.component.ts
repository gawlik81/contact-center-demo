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
import { catchError, EMPTY } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { EmailService, EmailTemplate } from '../../../services/email.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { CustomerSummary } from '../../../models/customer-search.model';

@Component({
  selector: 'app-adhoc-email-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, ReactiveFormsModule],
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

  readonly loading = signal(false);
  readonly visible = signal(false);
  readonly errorMessage = signal<string | null>(null);

  readonly templates = signal<EmailTemplate[]>([]);
  readonly templatesLoading = signal(false);
  readonly selectedTemplate = signal<EmailTemplate | null>(null);
  readonly templateVariables = signal<Record<string, string>>({});

  readonly hasMultipleEmails = computed(() => this.customer().email.length > 1);

  readonly selectedTemplateHasVariables = computed(
    () => (this.selectedTemplate()?.variables.length ?? 0) > 0,
  );

  readonly customerDisplayName = computed(() => {
    const c = this.customer();
    return [c.firstName, c.lastName].filter(Boolean).join(' ') || 'Klient';
  });

  readonly form = this.fb.group({
    templateId: [null as string | null],
    toAddress: ['', [Validators.required, Validators.email]],
    subject: ['', [Validators.required, Validators.maxLength(500)]],
    bodyHtml: ['', [Validators.required]],
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
    const ctrl = this.form.controls.bodyHtml;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required'))
      return this.translocoService.translate('agent.adhocEmail.bodyRequired');
    return null;
  }

  get isSubmitDisabled(): boolean {
    return this.form.invalid || this.form.pending || this.loading();
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
      this.form.controls.bodyHtml.patchValue('');
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
      .previewTemplate(template.id, this.templateVariables())
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
        this.form.controls.bodyHtml.patchValue(preview.bodyHtml);
      });
  }

  templateVariableKeys(): string[] {
    return Object.keys(this.templateVariables());
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
    if (this.isSubmitDisabled) return;

    this.errorMessage.set(null);
    this.loading.set(true);

    const raw = this.form.getRawValue();

    this.emailService
      .sendOutbound({
        toAddress: raw.toAddress!.trim(),
        subject: raw.subject!.trim(),
        bodyHtml: raw.bodyHtml!,
        customerId: this.customer().customerId,
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
        this.notifications.success(this.translocoService.translate('agent.adhocEmail.sent'));
        this.close();
        this.sent.emit();
      });
  }
}
