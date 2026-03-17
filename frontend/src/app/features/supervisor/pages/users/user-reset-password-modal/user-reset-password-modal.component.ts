import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
  input,
  output,
  viewChild,
} from '@angular/core';
import { UserResponse } from '../../../models/user.model';

@Component({
  selector: 'app-user-reset-password-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [],
  templateUrl: './user-reset-password-modal.component.html',
  styleUrl: './user-reset-password-modal.component.scss',
})
export class UserResetPasswordModalComponent implements AfterViewInit, OnDestroy {
  readonly user = input.required<UserResponse>();

  readonly confirmed = output<void>();
  readonly cancelled = output<void>();

  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('dialogEl');

  ngAfterViewInit(): void {
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog && !dialog.open) {
      dialog.showModal();
    }
    document.addEventListener('keydown', this.onKeyDown);
  }

  ngOnDestroy(): void {
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog?.open) {
      dialog.close();
    }
    document.removeEventListener('keydown', this.onKeyDown);
  }

  private readonly onKeyDown = (event: KeyboardEvent): void => {
    if (event.key === 'Escape') {
      event.preventDefault();
      this.onCancel();
    }
  };

  onConfirm(): void {
    this.confirmed.emit();
  }

  onCancel(): void {
    this.cancelled.emit();
  }
}
