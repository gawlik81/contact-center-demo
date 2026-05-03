import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
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
  const transloco = inject(TranslocoService);

  return next(req).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse) {
        handleHttpError(error, notifications, transloco);
      }
      return throwError(() => error);
    }),
  );
};

function handleHttpError(
  error: HttpErrorResponse,
  notifications: NotificationService,
  transloco: TranslocoService,
): void {
  // Network error or server unreachable (status 0)
  if (error.status === 0) {
    notifications.error(transloco.translate('common.errorNetwork'));
    return;
  }

  // 401 is handled by authInterceptor (silent refresh + logout redirect) — skip toast
  if (error.status === 401) {
    return;
  }

  if (error.status === 403) {
    notifications.error(transloco.translate('common.errorForbidden'));
    return;
  }

  if (error.status === 404) {
    // Only show toast for API calls, not for asset/route not-found
    if (isApiRequest(error.url)) {
      notifications.warning(transloco.translate('common.errorNotFound'));
    }
    return;
  }

  if (error.status >= 500) {
    notifications.error(transloco.translate('common.errorServer'));
    return;
  }

  // 400/409/422 and other 4xx — surface the server message if available
  if (error.status >= 400) {
    const serverMessage = extractServerMessage(error);
    notifications.warning(
      serverMessage !== null ? serverMessage : transloco.translate('common.errorBadRequest'),
    );
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
