import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { NotificationService, Toast } from '../../../core/services/notification.service';

@Component({
  selector: 'cc-toast-container',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'cc-toast-container',
    'aria-live': 'polite',
    'aria-atomic': 'false',
    role: 'region',
    'aria-label': 'Powiadomienia',
  },
  styles: [
    `
      :host.cc-toast-container {
        position: fixed;
        bottom: 1.5rem;
        right: 1.5rem;
        z-index: 9999;
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
        max-width: 22rem;
        width: calc(100vw - 3rem);
        pointer-events: none;
      }

      .toast {
        display: flex;
        align-items: flex-start;
        gap: 0.75rem;
        padding: 0.75rem 1rem;
        border-radius: 0.5rem;
        box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
        pointer-events: all;
        animation: slideIn 0.2s ease-out;
        font-size: 0.875rem;
        line-height: 1.4;
      }

      @keyframes slideIn {
        from {
          transform: translateX(110%);
          opacity: 0;
        }
        to {
          transform: translateX(0);
          opacity: 1;
        }
      }

      .toast--success {
        background: #d1fae5;
        border-left: 4px solid #059669;
        color: #065f46;
      }

      .toast--error {
        background: #fee2e2;
        border-left: 4px solid #dc2626;
        color: #7f1d1d;
      }

      .toast--warning {
        background: #fef3c7;
        border-left: 4px solid #d97706;
        color: #78350f;
      }

      .toast--info {
        background: #dbeafe;
        border-left: 4px solid #2563eb;
        color: #1e3a5f;
      }

      .toast__icon {
        flex-shrink: 0;
        font-size: 1.1rem;
        line-height: 1.4;
        user-select: none;
        aria-hidden: true;
      }

      .toast__message {
        flex: 1;
        word-break: break-word;
      }

      .toast__close {
        flex-shrink: 0;
        background: none;
        border: none;
        cursor: pointer;
        padding: 0.125rem 0.25rem;
        border-radius: 0.25rem;
        line-height: 1;
        font-size: 1rem;
        opacity: 0.6;
        color: inherit;
        transition: opacity 0.15s;
      }

      .toast__close:hover,
      .toast__close:focus-visible {
        opacity: 1;
        outline: 2px solid currentColor;
        outline-offset: 2px;
      }
    `,
  ],
  template: `
    @for (toast of notifications.toasts(); track toast.id) {
      <div
        class="toast"
        [class]="'toast toast--' + toast.type"
        role="alert"
        [attr.aria-label]="ariaLabel(toast)"
      >
        <span class="toast__icon" aria-hidden="true">{{ icon(toast.type) }}</span>
        <span class="toast__message">{{ toast.message }}</span>
        <button
          class="toast__close"
          type="button"
          [attr.aria-label]="transloco.translate('toast.closeLabel') + toast.message"
          (click)="notifications.dismiss(toast.id)"
        >
          &#x2715;
        </button>
      </div>
    }
  `,
})
export class ToastContainerComponent {
  readonly notifications = inject(NotificationService);
  readonly transloco = inject(TranslocoService);

  icon(type: Toast['type']): string {
    switch (type) {
      case 'success':
        return '✓';
      case 'error':
        return '✕';
      case 'warning':
        return '⚠';
      case 'info':
        return 'ℹ';
    }
  }

  ariaLabel(toast: Toast): string {
    const prefix = this.transloco.translate(`toast.${toast.type}`);
    return `${prefix}: ${toast.message}`;
  }
}
