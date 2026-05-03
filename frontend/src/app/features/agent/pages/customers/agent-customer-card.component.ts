import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core';
import { DatePipe } from '@angular/common';
import { TranslocoModule } from '@jsverse/transloco';
import { CustomerSummary } from '../../models/customer-search.model';

@Component({
  selector: 'app-agent-customer-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, TranslocoModule],
  template: `
    <div class="customer-card">
      <div class="customer-card__avatar" aria-hidden="true">{{ initials }}</div>

      <div class="customer-card__body">
        <p class="customer-card__name">
          {{ customer.firstName || '' }} {{ customer.lastName || '' }}
        </p>

        @if (customer.phone.length > 0) {
          <p class="customer-card__meta">
            <svg
              class="customer-card__meta-icon"
              aria-hidden="true"
              viewBox="0 0 24 24"
              fill="currentColor"
            >
              <path
                d="M6.62 10.79c1.44 2.83 3.76 5.14 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z"
              />
            </svg>
            {{ customer.phone[0] }}
          </p>
        }

        @if (customer.email.length > 0) {
          <p class="customer-card__meta">
            <svg
              class="customer-card__meta-icon"
              aria-hidden="true"
              viewBox="0 0 24 24"
              fill="currentColor"
            >
              <path
                d="M20 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 4l-8 5-8-5V6l8 5 8-5v2z"
              />
            </svg>
            {{ customer.email[0] }}
          </p>
        }

        <p class="customer-card__meta customer-card__meta--muted">
          @if (customer.lastContactAt) {
            {{ 'agent.customers.lastContact' | transloco }}: {{ customer.lastContactAt | date: 'dd.MM.yyyy HH:mm' }}
          } @else {
            {{ 'agent.customers.noContacts' | transloco }}
          }
        </p>
      </div>

      <div class="customer-card__actions">
        <button
          type="button"
          class="customer-card__btn customer-card__btn--primary"
          [attr.aria-label]="
            ('agent.customers.detailsLabel' | transloco) + ' ' + (customer.firstName || '') + ' ' + (customer.lastName || '')
          "
          (click)="viewDetails.emit(customer)"
        >
          {{ 'agent.customers.details' | transloco }}
        </button>
        <button
          type="button"
          class="customer-card__btn customer-card__btn--secondary"
          [attr.aria-label]="
            ('agent.customers.callbackFor' | transloco) + ' ' + (customer.firstName || '') + ' ' + (customer.lastName || '')
          "
          (click)="scheduleCallback.emit(customer)"
        >
          {{ 'agent.customers.scheduleCallback' | transloco }}
        </button>
      </div>
    </div>
  `,
  styles: `
    :host {
      display: block;
    }

    .customer-card {
      display: flex;
      align-items: flex-start;
      gap: 0.75rem;
      padding: 0.875rem 1rem;
      background: #fff;
      border: 1px solid #e2e8f0;
      border-radius: 8px;
      transition:
        box-shadow 120ms ease,
        border-color 120ms ease;

      &:hover {
        border-color: #cbd5e1;
        box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
      }
    }

    .customer-card__avatar {
      width: 2.5rem;
      height: 2.5rem;
      border-radius: 50%;
      background: linear-gradient(135deg, #3b82f6 0%, #8b5cf6 100%);
      color: #fff;
      font-size: 0.875rem;
      font-weight: 700;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
      letter-spacing: 0.02em;
    }

    .customer-card__body {
      flex: 1;
      min-width: 0;
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    .customer-card__name {
      margin: 0;
      font-size: 0.9375rem;
      font-weight: 700;
      color: #1e293b;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      line-height: 1.3;
    }

    .customer-card__meta {
      display: flex;
      align-items: center;
      gap: 0.25rem;
      margin: 0;
      font-size: 0.8125rem;
      color: #475569;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;

      &--muted {
        color: #94a3b8;
        font-size: 0.75rem;
      }
    }

    .customer-card__meta-icon {
      width: 0.875rem;
      height: 0.875rem;
      flex-shrink: 0;
      opacity: 0.6;
    }

    .customer-card__actions {
      display: flex;
      flex-direction: column;
      gap: 0.375rem;
      flex-shrink: 0;
    }

    .customer-card__btn {
      padding: 0.3125rem 0.75rem;
      border-radius: 6px;
      font-size: 0.75rem;
      font-weight: 600;
      cursor: pointer;
      border: 1.5px solid;
      white-space: nowrap;
      transition:
        background 120ms ease,
        color 120ms ease;

      &:focus-visible {
        outline: 2px solid #1a56db;
        outline-offset: 2px;
      }

      &--primary {
        background: #1a56db;
        color: #fff;
        border-color: #1a56db;

        &:hover {
          background: #1d4ed8;
          border-color: #1d4ed8;
        }
      }

      &--secondary {
        background: transparent;
        color: #1a56db;
        border-color: #1a56db;

        &:hover {
          background: #eff6ff;
        }
      }
    }
  `,
})
export class AgentCustomerCardComponent {
  @Input({ required: true }) customer!: CustomerSummary;
  @Output() viewDetails = new EventEmitter<CustomerSummary>();
  @Output() scheduleCallback = new EventEmitter<CustomerSummary>();

  get initials(): string {
    const first = (this.customer.firstName ?? '').charAt(0).toUpperCase();
    const last = (this.customer.lastName ?? '').charAt(0).toUpperCase();
    return first + last || '?';
  }
}
