import { HttpClient, HttpContext, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotificationService } from '../services/notification.service';
import { errorHandlerInterceptor, SKIP_ERROR_TOAST } from './error-handler.interceptor';

describe('errorHandlerInterceptor', () => {
  const notifications = { error: vi.fn(), warning: vi.fn() };
  const transloco = { translate: vi.fn((key: string) => `t:${key}`) };
  let http: HttpClient;
  let ctrl: HttpTestingController;

  beforeEach(() => {
    vi.clearAllMocks();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([errorHandlerInterceptor])),
        provideHttpClientTesting(),
        { provide: NotificationService, useValue: notifications },
        { provide: TranslocoService, useValue: transloco },
      ],
    });
    http = TestBed.inject(HttpClient);
    ctrl = TestBed.inject(HttpTestingController);
  });

  function fail(body: object | string, status = 400, context?: HttpContext): void {
    http.get('/api/x', { context }).subscribe({ error: () => undefined });
    ctrl.expectOne('/api/x').flush(body, { status, statusText: 'err' });
  }

  it('uses body.message first', () => {
    fail({ message: 'M', error: 'E', detail: 'D' });
    expect(notifications.warning).toHaveBeenCalledWith('M');
  });

  it('uses body.detail from a problem+json body (before error code)', () => {
    fail({ title: 'T', detail: 'D', error: 'RESOURCE_LIMIT_EXCEEDED' }, 422);
    expect(notifications.warning).toHaveBeenCalledWith('D');
  });

  it('uses body.error when there is no message or detail', () => {
    fail({ error: 'Bad Request' });
    expect(notifications.warning).toHaveBeenCalledWith('Bad Request');
  });

  it('falls back to the default message', () => {
    fail({ title: 'T', status: 400 });
    expect(notifications.warning).toHaveBeenCalledWith('t:common.errorBadRequest');
  });

  it('ignores overly long detail', () => {
    fail({ detail: 'x'.repeat(301) });
    expect(notifications.warning).toHaveBeenCalledWith('t:common.errorBadRequest');
  });

  it('shows a short plain-string body, trimmed', () => {
    fail('  Zly format  ');
    expect(notifications.warning).toHaveBeenCalledWith('Zly format');
  });

  it('ignores a plain-string body longer than 300 characters', () => {
    fail('x'.repeat(301));
    expect(notifications.warning).toHaveBeenCalledWith('t:common.errorBadRequest');
  });

  it('ignores an HTML error page from a proxy / WAF', () => {
    fail('  <html><body>Blocked by WAF</body></html>');
    expect(notifications.warning).toHaveBeenCalledWith('t:common.errorBadRequest');
  });

  it('skips a non-string detail and falls through to the next key', () => {
    fail({ detail: { nested: true }, error: 'Bad Request' });
    expect(notifications.warning).toHaveBeenCalledWith('Bad Request');
  });

  it('shows detail of a ResourceLimitExceeded body, not its machine code', () => {
    fail({ error: 'RESOURCE_LIMIT_EXCEEDED', detail: 'Limit agentow zostal osiagniety' }, 422);
    expect(notifications.warning).toHaveBeenCalledWith('Limit agentow zostal osiagniety');
  });

  it('never shows detail for 5xx', () => {
    fail({ detail: 'NullPointerException at Foo' }, 500);
    expect(notifications.error).toHaveBeenCalledWith('t:common.errorServer');
    expect(notifications.warning).not.toHaveBeenCalled();
  });

  it('respects SKIP_ERROR_TOAST', () => {
    fail({ detail: 'D' }, 400, new HttpContext().set(SKIP_ERROR_TOAST, true));
    expect(notifications.warning).not.toHaveBeenCalled();
    expect(notifications.error).not.toHaveBeenCalled();
  });
});
