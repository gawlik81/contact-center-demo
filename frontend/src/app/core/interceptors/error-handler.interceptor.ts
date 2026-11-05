import { HttpContextToken, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { catchError, throwError } from 'rxjs';
import { NotificationService } from '../services/notification.service';

/** Pass this token via HttpContext to suppress the global error toast for a request. */
export const SKIP_ERROR_TOAST = new HttpContextToken<boolean>(() => false);

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
      if (error instanceof HttpErrorResponse && !req.context.get(SKIP_ERROR_TOAST)) {
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

/** Longer texts are most likely stack traces / technical dumps, not user copy. */
const MAX_SERVER_MESSAGE_LENGTH = 300;

/**
 * Only reached for 4xx (5xx, 401, 403, 404 and status 0 return earlier), so `detail` is the
 * client-error description from the backend ProblemDetail handlers (user-oriented Polish copy).
 *
 * Order: message -> detail -> error. `detail` goes before `error` on purpose: in problem+json
 * bodies `error` can be a machine code (e.g. "RESOURCE_LIMIT_EXCEEDED"), while legacy Spring
 * bodies ({ error: "Bad Request" }) have no `detail`, so they still fall through to `error`.
 */
function extractServerMessage(error: HttpErrorResponse): string | null {
  if (!error.error) return null;
  if (typeof error.error === 'string') return usableServerText(error.error);
  if (typeof error.error === 'object') {
    const body = error.error as Record<string, unknown>;
    for (const key of ['message', 'detail', 'error']) {
      const text = usableServerText(body[key]);
      if (text !== null) return text;
    }
  }
  return null;
}

/**
 * Trimmed, short, non-HTML text or null. A plain-string body is typically an error page from a
 * proxy/WAF (HTML), which must never reach a toast.
 */
function usableServerText(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const text = value.trim();
  if (!text || text.length > MAX_SERVER_MESSAGE_LENGTH || text.startsWith('<')) return null;
  return text;
}
