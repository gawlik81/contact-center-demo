import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { NotificationService } from '../../../../core/services/notification.service';

type CallbackState = 'processing' | 'success' | 'error';

@Component({
  selector: 'app-oauth-callback',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="callback-page">
      <div class="callback-card">
        @switch (state()) {
          @case ('processing') {
            <div
              class="callback-card__spinner"
              aria-label="Przetwarzanie autoryzacji"
              role="status"
            >
              <span class="spinner" aria-hidden="true"></span>
            </div>
            <p class="callback-card__message">Przetwarzanie autoryzacji...</p>
          }
          @case ('success') {
            <div class="callback-card__icon callback-card__icon--success" aria-hidden="true">
              <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor">
                <path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z" />
              </svg>
            </div>
            <p class="callback-card__message callback-card__message--success">
              Połączono pomyślnie z <strong>{{ platformLabel() }}</strong
              >!
            </p>
            <p class="callback-card__hint">Za chwilę nastąpi przekierowanie...</p>
          }
          @case ('error') {
            <div class="callback-card__icon callback-card__icon--error" aria-hidden="true">
              <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor">
                <path
                  d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"
                />
              </svg>
            </div>
            <p class="callback-card__message callback-card__message--error">{{ errorMessage() }}</p>
            <p class="callback-card__hint">
              Za chwilę nastąpi przekierowanie do ustawień integracji...
            </p>
          }
        }
      </div>
    </div>
  `,
  styles: [
    `
      .callback-page {
        min-height: 100vh;
        display: flex;
        align-items: center;
        justify-content: center;
        background: #f9fafb;
        padding: 1rem;
      }

      .callback-card {
        background: #fff;
        border-radius: 16px;
        box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
        padding: 2.5rem 2rem;
        text-align: center;
        max-width: 380px;
        width: 100%;

        &__spinner {
          display: flex;
          justify-content: center;
          margin-bottom: 1.25rem;
        }

        &__icon {
          display: flex;
          align-items: center;
          justify-content: center;
          width: 56px;
          height: 56px;
          border-radius: 50%;
          margin: 0 auto 1.25rem;

          svg {
            width: 32px;
            height: 32px;
          }

          &--success {
            background: #d1fae5;
            color: #059669;
          }

          &--error {
            background: #fee2e2;
            color: #dc2626;
          }
        }

        &__message {
          font-size: 1rem;
          color: #111827;
          margin: 0 0 0.5rem;
          line-height: 1.5;

          &--success {
            color: #065f46;
          }

          &--error {
            color: #991b1b;
          }
        }

        &__hint {
          font-size: 0.8125rem;
          color: #9ca3af;
          margin: 0;
        }
      }

      .spinner {
        display: inline-block;
        width: 40px;
        height: 40px;
        border: 3px solid #e5e7eb;
        border-top-color: #2563eb;
        border-radius: 50%;
        animation: spin 0.8s linear infinite;
      }

      @keyframes spin {
        to {
          transform: rotate(360deg);
        }
      }
    `,
  ],
})
export class OauthCallbackComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly notifications = inject(NotificationService);

  readonly state = signal<CallbackState>('processing');
  readonly platformLabel = signal('');
  readonly errorMessage = signal('');

  ngOnInit(): void {
    const platform = (this.route.snapshot.paramMap.get('platform') ?? '').toUpperCase();
    const queryParams = this.route.snapshot.queryParamMap;
    const error = queryParams.get('error');

    this.platformLabel.set(platform);

    if (error) {
      this.state.set('error');
      this.errorMessage.set('Odmowa dostępu przez użytkownika.');
      this.notifications.error('Odmowa dostępu przez użytkownika.');
    } else {
      this.state.set('success');
      this.notifications.success(`Połączono pomyślnie z ${platform}!`);
    }

    setTimeout(() => {
      void this.router.navigate(['/supervisor/settings/integrations']);
    }, 2000);
  }
}
