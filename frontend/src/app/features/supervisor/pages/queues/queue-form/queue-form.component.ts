import { TranslocoModule } from '@jsverse/transloco';
import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnDestroy,
  OnInit,
  computed,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { catchError, forkJoin, of } from 'rxjs';
import { QueueService } from '../../../services/queue.service';
import { UserService } from '../../../services/user.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { Queue } from '../../../models/queue.model';
import { QueueAgentsModalComponent } from '../queue-agents-modal/queue-agents-modal.component';

@Component({
  selector: 'app-queue-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, ReactiveFormsModule, QueueAgentsModalComponent],
  templateUrl: './queue-form.component.html',
  styleUrl: './queue-form.component.scss',
  host: {
    '(document:keydown.escape)': 'onEscapeKey($event)',
  },
})
export class QueueFormComponent implements OnInit, AfterViewInit, OnDestroy {
  readonly queue = input<Queue | null>(null);
  readonly isEditMode = input<boolean>(false);

  readonly saved = output<void>();
  readonly cancelled = output<void>();

  private readonly queueService = inject(QueueService);
  private readonly userService = inject(UserService);
  private readonly notifications = inject(NotificationService);
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('dialogEl');

  private blurTimerId: ReturnType<typeof setTimeout> | null = null;

  readonly submitting = signal(false);
  readonly loadingOptions = signal(false);
  readonly showAgentsModal = signal(false);
  readonly routingStrategies = signal<string[]>([]);
  readonly availableSkills = signal<string[]>([]);
  readonly selectedSkills = signal<string[]>([]);
  readonly skillInput = signal('');
  readonly showSkillDropdown = signal(false);

  readonly form = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(100)]],
    routingStrategy: ['', Validators.required],
    emailAddress: ['', [Validators.email, Validators.maxLength(255)]],
    active: [true],
  });

  ngOnInit(): void {
    this.loadOptions();

    const editQueue = this.queue();
    if (this.isEditMode() && editQueue) {
      this.form.patchValue({
        name: editQueue.name,
        routingStrategy: editQueue.routingStrategy,
        emailAddress: editQueue.emailAddress ?? '',
        active: editQueue.active,
      });
      this.selectedSkills.set([...editQueue.requiredSkills]);
    }

    if (!this.isEditMode()) {
      this.form.get('active')?.setValue(true);
    }
  }

  ngAfterViewInit(): void {
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog && !dialog.open) {
      dialog.showModal();
    }
  }

  onEscapeKey(event: Event): void {
    event.preventDefault();
    this.onCancel();
  }

  private loadOptions(): void {
    this.loadingOptions.set(true);
    forkJoin({
      strategies: this.queueService.getRoutingStrategies().pipe(catchError(() => of<string[]>([]))),
      skills: this.userService.getSkills().pipe(catchError(() => of<string[]>([]))),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(({ strategies, skills }) => {
        this.routingStrategies.set(strategies);
        this.availableSkills.set(skills);
        this.loadingOptions.set(false);

        if (
          !this.isEditMode() &&
          strategies.length > 0 &&
          !this.form.get('routingStrategy')?.value
        ) {
          this.form.get('routingStrategy')?.setValue(strategies[0]);
        }
      });
  }

  readonly filteredSkills = computed(() => {
    const inputVal = this.skillInput().toLowerCase();
    const selected = this.selectedSkills();
    return this.availableSkills().filter(
      (s) => !selected.includes(s) && s.toLowerCase().includes(inputVal),
    );
  });

  onSkillInputChange(event: Event): void {
    const val = (event.target as HTMLInputElement).value;
    this.skillInput.set(val);
    this.showSkillDropdown.set(val.trim().length > 0 || this.filteredSkills().length > 0);
  }

  onSkillInputFocus(): void {
    this.showSkillDropdown.set(true);
  }

  onSkillInputBlur(): void {
    this.blurTimerId = setTimeout(() => this.showSkillDropdown.set(false), 150);
  }

  ngOnDestroy(): void {
    if (this.blurTimerId !== null) {
      clearTimeout(this.blurTimerId);
    }
  }

  onSkillInputKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter') {
      event.preventDefault();
      const val = this.skillInput().trim();
      if (val) {
        this.addSkill(val);
      }
    }
  }

  addSkill(skill: string): void {
    const trimmed = skill.trim();
    if (!trimmed || this.selectedSkills().includes(trimmed)) return;
    this.selectedSkills.update((list) => [...list, trimmed]);
    this.skillInput.set('');
    this.showSkillDropdown.set(false);
  }

  removeSkill(skill: string): void {
    this.selectedSkills.update((list) => list.filter((s) => s !== skill));
  }

  get nameError(): string | null {
    const ctrl = this.form.get('name')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Nazwa jest wymagana.';
    if (ctrl.hasError('maxlength')) return 'Nazwa nie moze przekraczac 100 znakow.';
    return null;
  }

  get routingStrategyError(): string | null {
    const ctrl = this.form.get('routingStrategy')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Strategia routingu jest wymagana.';
    return null;
  }

  get emailAddressError(): string | null {
    const ctrl = this.form.get('emailAddress')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('email')) return 'Podaj prawidlowy adres email.';
    if (ctrl.hasError('maxlength')) return 'Adres email nie moze przekraczac 255 znakow.';
    return null;
  }

  get isSaveDisabled(): boolean {
    return this.form.invalid || this.submitting();
  }

  onSubmit(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.submitting()) return;

    this.submitting.set(true);
    const raw = this.form.getRawValue();
    const skills = this.selectedSkills();

    const emailAddress = raw.emailAddress?.trim() || null;
    const editQueue = this.queue();
    if (this.isEditMode() && editQueue) {
      this.queueService
        .updateQueue(editQueue.queueId, {
          name: raw.name!.trim(),
          routingStrategy: raw.routingStrategy!,
          requiredSkills: skills,
          emailAddress,
          active: raw.active ?? true,
        })
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: () => {
            this.submitting.set(false);
            this.notifications.success(`Kolejka "${raw.name}" zostala zaktualizowana.`);
            this.saved.emit();
          },
          error: () => {
            this.submitting.set(false);
            this.notifications.error('Nie udalo sie zaktualizowac kolejki. Sprobuj ponownie.');
          },
        });
    } else {
      this.queueService
        .createQueue({
          name: raw.name!.trim(),
          routingStrategy: raw.routingStrategy!,
          requiredSkills: skills,
          emailAddress,
        })
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (created) => {
            this.submitting.set(false);
            this.notifications.success(`Kolejka "${created.name}" zostala utworzona.`);
            this.saved.emit();
          },
          error: () => {
            this.submitting.set(false);
            this.notifications.error('Nie udalo sie utworzyc kolejki. Sprobuj ponownie.');
          },
        });
    }
  }

  onCancel(): void {
    this.cancelled.emit();
  }
}
