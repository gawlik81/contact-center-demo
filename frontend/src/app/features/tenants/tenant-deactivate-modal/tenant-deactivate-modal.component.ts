import {
  ChangeDetectionStrategy,
  Component,
  AfterViewInit,
  ElementRef,
  input,
  output,
  viewChild,
} from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';
import { Tenant } from '../tenant.model';

@Component({
  selector: 'app-tenant-deactivate-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule],
  templateUrl: './tenant-deactivate-modal.component.html',
  styleUrl: './tenant-deactivate-modal.component.scss',
  host: {
    '(document:keydown.escape)': 'onEscapeKey($event)',
  },
})
export class TenantDeactivateModalComponent implements AfterViewInit {
  readonly tenant = input.required<Tenant>();

  readonly confirmed = output<void>();
  readonly cancelled = output<void>();

  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('dialogEl');

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

  onConfirm(): void {
    this.confirmed.emit();
  }

  onCancel(): void {
    this.cancelled.emit();
  }
}
