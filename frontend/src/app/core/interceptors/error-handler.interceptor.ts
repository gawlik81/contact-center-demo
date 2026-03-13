import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { NotificationService } from '../services/notification.service';

/**
 * Global HTTP error handler.
 * Translates HTTP error statuses into user-facing toast notifications.
 *
 * This interceptor runs AFTER authInterceptor in the chain, so by the time
 * a 401 reaches here the authInterceptor has already attempted a silent refresh.
 * A 401 that still arrives here means refresh failed and the user is being
 * redirected to /login — no toast needed for that case.
 */
export const errorHandlerInterceptor: HttpInterceptorFn = (req, next) => {
  const notifications = inject(NotificationService);

  return next(req).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse) {
        handleHttpError(error, notifications);
      }
      return throwError(() => error);
    }),
  );
};

function handleHttpError(error: HttpErrorResponse, notifications: NotificationService): void {
  // Network error or server unreachable (status 0)
  if (error.status === 0) {
    notifications.error('Brak połączenia z serwerem');
    return;
  }

  // 401 is handled by authInterceptor (silent refresh + logout redirect) — skip toast
  if (error.status === 401) {
    return;
  }

  if (error.status === 403) {
    notifications.error('Brak uprawnień');
    return;
  }

  if (error.status === 404) {
    // Only show toast for API calls, not for asset/route not-found
    if (isApiRequest(error.url)) {
      notifications.warning('Zasób nie został znaleziony');
    }
    return;
  }

  if (error.status >= 500) {
    notifications.error('Błąd serwera, spróbuj ponownie');
    return;
  }

  // 400/409/422 and other 4xx — surface the server message if available
  if (error.status >= 400) {
    const message = extractServerMessage(error) ?? 'Wystąpił błąd. Sprawdź dane i spróbuj ponownie.';
    notifications.warning(message);
  }
}

function isApiRequest(url: string | null): boolean {
  if (!url) return false;
  return url.includes('/api/');
}

function extractServerMessage(error: HttpErrorResponse): string | null {
  if (!error.error) return null;
  if (typeof error.error === 'string') return error.error;
  if (typeof error.error === 'object') {
    // Spring Boot error body: { message: '...' } or { error: '...' }
    const body = error.error as Record<string, unknown>;
    if (typeof body['message'] === 'string') return body['message'];
    if (typeof body['error'] === 'string') return body['error'];
  }
  return null;
}
