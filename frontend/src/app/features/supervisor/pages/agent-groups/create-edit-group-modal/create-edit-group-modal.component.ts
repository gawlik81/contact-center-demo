import { TranslocoModule } from '@jsverse/transloco';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { catchError, finalize, of } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { AgentGroupService } from '../../../../../core/services/agent-group.service';
import { AgentGroup } from '../../../../../core/models/agent-group.model';

@Component({
  selector: 'app-create-edit-group-modal',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslocoModule,ReactiveFormsModule],
  templateUrl: './create-edit-group-modal.component.html',
  styleUrl: './create-edit-group-modal.component.scss',
})
export class CreateEditGroupModalComponent implements OnInit {
  private readonly agentGroupService = inject(AgentGroupService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly fb = inject(FormBuilder);

  readonly group = input<AgentGroup | null>(null);

  readonly saved = output<AgentGroup>();
  readonly cancelled = output<void>();

  readonly saving = signal<boolean>(false);

  readonly form = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(255)]],
  });

  get nameControl() {
    return this.form.controls.name;
  }

  get isEditMode(): boolean {
    return this.group() !== null;
  }

  ngOnInit(): void {
    const g = this.group();
    if (g) {
      this.form.patchValue({ name: g.name });
    }
  }

  onSubmit(): void {
    if (this.form.invalid || this.saving()) return;
    this.form.markAllAsTouched();
    if (this.form.invalid) return;

    const name = this.nameControl.value!.trim();
    const g = this.group();
    const request$ = g
      ? this.agentGroupService.updateGroup(g.groupId, name)
      : this.agentGroupService.createGroup(name);

    this.saving.set(true);
    request$
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError((err: HttpErrorResponse) => {
          if (err.status === 409) {
            this.nameControl.setErrors({ conflict: true });
          } else {
            this.nameControl.setErrors({ serverError: true });
          }
          return of(null);
        }),
        finalize(() => this.saving.set(false)),
      )
      .subscribe((result) => {
        if (result) {
          this.saved.emit(result);
        }
      });
  }

  onCancel(): void {
    this.cancelled.emit();
  }
}
