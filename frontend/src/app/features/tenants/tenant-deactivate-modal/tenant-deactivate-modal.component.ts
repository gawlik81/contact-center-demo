import {
  ChangeDetectionStrategy,
  Component,
  AfterViewInit,
  OnDestroy,
  ElementRef,
  input,
  output,
  viewChild,
} from '@angular/core';
import { Tenant } from '../tenant.model';

@Component({
  selector: 'app-tenant-deactivate-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [],
  templateUrl: './tenant-deactivate-modal.component.html',
  styleUrl: './tenant-deactivate-modal.component.scss',
})
export class TenantDeactivateModalComponent implements AfterViewInit, OnDestroy {
  readonly tenant = input.required<Tenant>();

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
