import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  ViewChild,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { EmailService, EmailTemplate } from '../../../../agent/services/email.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { PagedResponse } from '../../../../../core/models/paged-response.model';

type ModalMode = 'create' | 'edit' | 'preview';

@Component({
  selector: 'app-email-templates',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './email-templates.component.html',
  styleUrl: './email-templates.component.scss',
})
export class EmailTemplatesComponent implements OnInit {
  @ViewChild('formDialog') private formDialogRef!: ElementRef<HTMLDialogElement>;
  @ViewChild('deleteDialog') private deleteDialogRef!: ElementRef<HTMLDialogElement>;
  @ViewChild('previewDialog') private previewDialogRef!: ElementRef<HTMLDialogElement>;

  private readonly emailService = inject(EmailService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly fb = inject(FormBuilder);

  readonly curlyOpen = '{{';
  readonly curlyClose = '}}';

  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly deleting = signal(false);
  readonly previewLoading = signal(false);

  readonly templates = signal<EmailTemplate[]>([]);
  readonly totalElements = signal(0);
  readonly currentPage = signal(0);
  readonly pageSize = 20;

  readonly selectedTemplate = signal<EmailTemplate | null>(null);
  readonly modalMode = signal<ModalMode>('create');
  readonly previewResult = signal<{ subject: string; bodyHtml: string } | null>(null);

  readonly totalPages = computed(() => Math.ceil(this.totalElements() / this.pageSize));
  readonly hasNextPage = computed(() => this.currentPage() < this.totalPages() - 1);
  readonly hasPrevPage = computed(() => this.currentPage() > 0);

  readonly form: FormGroup = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(200)]],
    subjectTemplate: ['', [Validators.required, Validators.maxLength(500)]],
    bodyHtml: ['', Validators.required],
    variables: this.fb.array([]),
  });

  readonly previewForm: FormGroup = this.fb.group({});

  get variablesArray(): FormArray {
    return this.form.get('variables') as FormArray;
  }

  ngOnInit(): void {
    this.loadTemplates();
  }

  loadTemplates(): void {
    this.loading.set(true);
    this.emailService
      .getTemplates(this.currentPage(), this.pageSize)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response: PagedResponse<EmailTemplate>) => {
          this.templates.set(response.content);
          this.totalElements.set(response.totalElements);
          this.loading.set(false);
        },
        error: () => {
          this.notifications.error('Nie udało się załadować szablonów email.');
          this.loading.set(false);
        },
      });
  }

  openCreateModal(): void {
    this.modalMode.set('create');
    this.selectedTemplate.set(null);
    this.form.reset({ name: '', subjectTemplate: '', bodyHtml: '' });
    this.clearVariablesArray();
    this.formDialogRef.nativeElement.showModal();
  }

  openEditModal(template: EmailTemplate): void {
    this.modalMode.set('edit');
    this.selectedTemplate.set(template);
    this.clearVariablesArray();
    template.variables.forEach((v) => this.addVariable(v));
    this.form.patchValue({
      name: template.name,
      subjectTemplate: template.subjectTemplate,
      bodyHtml: template.bodyHtml,
    });
    this.formDialogRef.nativeElement.showModal();
  }

  openDeleteModal(template: EmailTemplate): void {
    this.selectedTemplate.set(template);
    this.deleteDialogRef.nativeElement.showModal();
  }

  openPreviewModal(template: EmailTemplate): void {
    this.modalMode.set('preview');
    this.selectedTemplate.set(template);
    this.previewResult.set(null);
    this.buildPreviewForm(template.variables);
    this.previewDialogRef.nativeElement.showModal();
  }

  closeFormModal(): void {
    this.formDialogRef.nativeElement.close();
  }

  closeDeleteModal(): void {
    this.deleteDialogRef.nativeElement.close();
  }

  closePreviewModal(): void {
    this.previewDialogRef.nativeElement.close();
    this.previewResult.set(null);
  }

  addVariable(value = ''): void {
    this.variablesArray.push(this.fb.control(value, Validators.required));
  }

  removeVariable(index: number): void {
    this.variablesArray.removeAt(index);
  }

  saveTemplate(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    const request = {
      name: value.name.trim(),
      subjectTemplate: value.subjectTemplate.trim(),
      bodyHtml: value.bodyHtml,
      variables: (value.variables as string[]).map((v: string) => v.trim()).filter(Boolean),
    };

    this.saving.set(true);
    const mode = this.modalMode();
    const template = this.selectedTemplate();

    const op$ =
      mode === 'edit' && template
        ? this.emailService.updateTemplate(template.id, request)
        : this.emailService.createTemplate(request);

    op$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.closeFormModal();
        if (mode === 'edit') {
          this.templates.update((list) => list.map((t) => (t.id === saved.id ? saved : t)));
          this.notifications.success('Szablon został zaktualizowany.');
        } else {
          this.loadTemplates();
          this.notifications.success('Szablon został utworzony.');
        }
      },
      error: () => {
        this.saving.set(false);
        this.notifications.error('Nie udało się zapisać szablonu.');
      },
    });
  }

  confirmDelete(): void {
    const template = this.selectedTemplate();
    if (!template) return;
    this.deleting.set(true);
    const destroyRef = this.destroyRef;
    this.emailService
      .deleteTemplate(template.id)
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe({
        next: () => {
          this.deleting.set(false);
          this.closeDeleteModal();
          this.templates.update((list) => list.filter((t) => t.id !== template.id));
          this.totalElements.update((n) => n - 1);
          this.notifications.success('Szablon został usunięty.');
        },
        error: () => {
          this.deleting.set(false);
          this.notifications.error('Nie udało się usunąć szablonu.');
        },
      });
  }

  runPreview(): void {
    if (this.previewForm.invalid) {
      this.previewForm.markAllAsTouched();
      return;
    }
    const template = this.selectedTemplate();
    if (!template) return;
    const variables: Record<string, string> = this.previewForm.getRawValue();
    this.previewLoading.set(true);
    const destroyRef2 = this.destroyRef;
    this.emailService
      .previewTemplate(template.id, variables)
      .pipe(takeUntilDestroyed(destroyRef2))
      .subscribe({
        next: (result: { subject: string; bodyHtml: string }) => {
          this.previewResult.set(result);
          this.previewLoading.set(false);
        },
        error: () => {
          this.previewLoading.set(false);
          this.notifications.error('Nie udało się wygenerować podglądu.');
        },
      });
  }

  goToPage(page: number): void {
    this.currentPage.set(page);
    this.loadTemplates();
  }

  isFieldInvalid(fieldName: string): boolean {
    const ctrl = this.form.get(fieldName);
    return !!(ctrl && ctrl.invalid && ctrl.touched);
  }

  variableControlAt(index: number) {
    return this.variablesArray.at(index);
  }

  previewVariableKeys(): string[] {
    return Object.keys(this.previewForm.controls);
  }

  private clearVariablesArray(): void {
    while (this.variablesArray.length) {
      this.variablesArray.removeAt(0);
    }
  }

  private buildPreviewForm(variables: string[]): void {
    const group: Record<string, unknown> = {};
    variables.forEach((v) => {
      group[v] = [''];
    });
    const newForm = this.fb.group(group);
    Object.keys(newForm.controls).forEach((key) => {
      if (!this.previewForm.contains(key)) {
        this.previewForm.addControl(key, newForm.get(key)!);
      }
    });
    Object.keys(this.previewForm.controls).forEach((key) => {
      if (!variables.includes(key)) {
        this.previewForm.removeControl(key);
      }
    });
  }
}
