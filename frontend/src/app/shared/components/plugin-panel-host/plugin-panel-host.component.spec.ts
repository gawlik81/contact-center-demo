import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { PluginPanelHostComponent } from './plugin-panel-host.component';
import { NotificationService } from '../../../core/services/notification.service';

function buildNotificationServiceMock() {
  return {
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
    info: vi.fn(),
  };
}

/** Wysyła `MessageEvent` do `window`, z `source` ustawionym na `contentWindow` iframe pluginu. */
function dispatchPluginMessage(
  fixture: ComponentFixture<PluginPanelHostComponent>,
  data: unknown,
  origin = window.location.origin,
): void {
  const iframe: HTMLIFrameElement = fixture.nativeElement.querySelector('iframe');
  const event = new MessageEvent('message', {
    data,
    origin,
    source: iframe.contentWindow,
  });
  window.dispatchEvent(event);
}

describe('PluginPanelHostComponent', () => {
  let fixture: ComponentFixture<PluginPanelHostComponent>;
  let component: PluginPanelHostComponent;
  let httpMock: HttpTestingController;
  let notificationMock: ReturnType<typeof buildNotificationServiceMock>;

  function setup() {
    notificationMock = buildNotificationServiceMock();

    TestBed.configureTestingModule({
      imports: [PluginPanelHostComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: NotificationService, useValue: notificationMock },
      ],
    });

    fixture = TestBed.createComponent(PluginPanelHostComponent);
    fixture.componentRef.setInput('installationId', 'install-123');
    fixture.componentRef.setInput('mountPoint', 'AGENT_DESKTOP_SIDE_PANEL');
    fixture.componentRef.setInput('tenantId', 'tenant-abc');
    fixture.componentRef.setInput('contactId', 'contact-1');
    fixture.componentRef.setInput('customerId', 'customer-1');
    component = fixture.componentInstance;
    fixture.detectChanges();

    httpMock = TestBed.inject(HttpTestingController);
  }

  afterEach(() => {
    httpMock?.verify();
    TestBed.resetTestingModule();
  });

  // ── Rendering / sandbox (RT-11) ─────────────────────────────────────────────

  it('renders iframe with sandbox="allow-scripts allow-forms" and no other tokens', () => {
    setup();
    const iframe: HTMLIFrameElement = fixture.nativeElement.querySelector('iframe');
    expect(iframe.getAttribute('sandbox')).toBe('allow-scripts allow-forms');
  });

  it('renders iframe with referrerpolicy="no-referrer"', () => {
    setup();
    const iframe: HTMLIFrameElement = fixture.nativeElement.querySelector('iframe');
    expect(iframe.getAttribute('referrerpolicy')).toBe('no-referrer');
  });

  it('builds trustedPluginPanelUrl from installationId via the dedicated plugin-assets path', () => {
    setup();
    const iframe: HTMLIFrameElement = fixture.nativeElement.querySelector('iframe');
    expect(iframe.getAttribute('src')).toBe('/plugin-assets/install-123/index.html');
  });

  // ── Origin validation ────────────────────────────────────────────────────────

  it('ignores messages from an unexpected origin and logs a warning', () => {
    setup();
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);

    dispatchPluginMessage(
      fixture,
      { type: 'GET_CONTEXT', requestId: 'req-1' },
      'https://evil.example.com',
    );

    expect(warnSpy).toHaveBeenCalled();
    httpMock.expectNone(() => true);
    warnSpy.mockRestore();
  });

  it('processes messages from the expected (same) origin', () => {
    setup();
    const iframe: HTMLIFrameElement = fixture.nativeElement.querySelector('iframe');
    const postMessageSpy = vi.spyOn(iframe.contentWindow as Window, 'postMessage');

    dispatchPluginMessage(fixture, { type: 'GET_CONTEXT', requestId: 'req-1' });

    expect(postMessageSpy).toHaveBeenCalled();
  });

  // ── Shape validation ─────────────────────────────────────────────────────────

  it('ignores messages with an invalid shape (unknown type)', () => {
    setup();
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);

    dispatchPluginMessage(fixture, { type: 'SOMETHING_ELSE' });

    expect(warnSpy).toHaveBeenCalled();
    httpMock.expectNone(() => true);
    warnSpy.mockRestore();
  });

  it('ignores non-object message data', () => {
    setup();
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);

    dispatchPluginMessage(fixture, 'not-an-object');

    expect(warnSpy).toHaveBeenCalled();
    warnSpy.mockRestore();
  });

  // ── GET_CONTEXT ──────────────────────────────────────────────────────────────

  it('responds to GET_CONTEXT with only tenantId/contactId/customerId', () => {
    setup();
    const iframe: HTMLIFrameElement = fixture.nativeElement.querySelector('iframe');
    const postMessageSpy = vi.spyOn(iframe.contentWindow as Window, 'postMessage');

    dispatchPluginMessage(fixture, { type: 'GET_CONTEXT', requestId: 'req-1' });

    expect(postMessageSpy).toHaveBeenCalledWith(
      {
        type: 'GET_CONTEXT_RESULT',
        requestId: 'req-1',
        success: true,
        payload: { tenantId: 'tenant-abc', contactId: 'contact-1', customerId: 'customer-1' },
      },
      '*',
    );
  });

  it('does not call the backend for GET_CONTEXT', () => {
    setup();
    dispatchPluginMessage(fixture, { type: 'GET_CONTEXT', requestId: 'req-1' });
    httpMock.expectNone(() => true);
  });

  // ── INVOKE_MANUAL_ACTION ─────────────────────────────────────────────────────

  it('proxies INVOKE_MANUAL_ACTION through HttpClient and responds with the result on success', () => {
    setup();
    const iframe: HTMLIFrameElement = fixture.nativeElement.querySelector('iframe');
    const postMessageSpy = vi.spyOn(iframe.contentWindow as Window, 'postMessage');

    dispatchPluginMessage(fixture, {
      type: 'INVOKE_MANUAL_ACTION',
      requestId: 'req-2',
      payload: { actionId: 'open-crm', payload: { foo: 'bar' } },
    });

    const req = httpMock.expectOne('/api/agent/plugins/install-123/manual-action/open-crm');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      contactId: 'contact-1',
      customerId: 'customer-1',
      payload: { foo: 'bar' },
    });
    // JWT is never part of the postMessage body — it is added by the existing HttpInterceptor.
    expect(JSON.stringify(req.request.body)).not.toContain('Bearer');

    req.flush({ success: true, resultData: { opened: true }, message: null, error: null });

    expect(postMessageSpy).toHaveBeenCalledWith(
      {
        type: 'INVOKE_MANUAL_ACTION_RESULT',
        requestId: 'req-2',
        success: true,
        payload: { success: true, resultData: { opened: true }, message: null, error: null },
      },
      '*',
    );
  });

  it('responds with success: false on HTTP error from manual-action endpoint', () => {
    setup();
    const iframe: HTMLIFrameElement = fixture.nativeElement.querySelector('iframe');
    const postMessageSpy = vi.spyOn(iframe.contentWindow as Window, 'postMessage');

    dispatchPluginMessage(fixture, {
      type: 'INVOKE_MANUAL_ACTION',
      requestId: 'req-3',
      payload: { actionId: 'open-crm', payload: {} },
    });

    const req = httpMock.expectOne('/api/agent/plugins/install-123/manual-action/open-crm');
    req.flush({ error: 'Gateway timeout' }, { status: 504, statusText: 'Gateway Timeout' });

    expect(postMessageSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        type: 'INVOKE_MANUAL_ACTION_RESULT',
        requestId: 'req-3',
        success: false,
      }),
      '*',
    );
  });

  it('rejects INVOKE_MANUAL_ACTION with invalid payload without calling the backend', () => {
    setup();
    dispatchPluginMessage(fixture, {
      type: 'INVOKE_MANUAL_ACTION',
      requestId: 'req-4',
      payload: {},
    });
    httpMock.expectNone(() => true);
  });

  // ── REQUEST_RESIZE ───────────────────────────────────────────────────────────

  it('updates panelHeightPx on REQUEST_RESIZE within bounds', () => {
    setup();
    dispatchPluginMessage(fixture, { type: 'REQUEST_RESIZE', payload: { height: 450 } });
    expect(component.panelHeightPx()).toBe(450);
  });

  it('clamps REQUEST_RESIZE height to the maximum (800px)', () => {
    setup();
    dispatchPluginMessage(fixture, { type: 'REQUEST_RESIZE', payload: { height: 5000 } });
    expect(component.panelHeightPx()).toBe(800);
  });

  it('clamps REQUEST_RESIZE height to the minimum (100px)', () => {
    setup();
    dispatchPluginMessage(fixture, { type: 'REQUEST_RESIZE', payload: { height: 1 } });
    expect(component.panelHeightPx()).toBe(100);
  });

  it('ignores REQUEST_RESIZE with a non-numeric height', () => {
    setup();
    const before = component.panelHeightPx();
    dispatchPluginMessage(fixture, { type: 'REQUEST_RESIZE', payload: { height: 'tall' } });
    expect(component.panelHeightPx()).toBe(before);
  });

  // ── NOTIFY ────────────────────────────────────────────────────────────────────

  it('maps NOTIFY severity "info" to NotificationService.info', () => {
    setup();
    dispatchPluginMessage(fixture, {
      type: 'NOTIFY',
      payload: { message: 'Zsynchronizowano', severity: 'info' },
    });
    expect(notificationMock.info).toHaveBeenCalledWith('Zsynchronizowano');
  });

  it('maps NOTIFY severity "warning" to NotificationService.warning', () => {
    setup();
    dispatchPluginMessage(fixture, {
      type: 'NOTIFY',
      payload: { message: 'Uwaga', severity: 'warning' },
    });
    expect(notificationMock.warning).toHaveBeenCalledWith('Uwaga');
  });

  it('maps NOTIFY severity "error" to NotificationService.error', () => {
    setup();
    dispatchPluginMessage(fixture, {
      type: 'NOTIFY',
      payload: { message: 'Błąd', severity: 'error' },
    });
    expect(notificationMock.error).toHaveBeenCalledWith('Błąd');
  });

  it('ignores NOTIFY with an invalid severity', () => {
    setup();
    dispatchPluginMessage(fixture, {
      type: 'NOTIFY',
      payload: { message: 'X', severity: 'success' },
    });
    expect(notificationMock.info).not.toHaveBeenCalled();
    expect(notificationMock.warning).not.toHaveBeenCalled();
    expect(notificationMock.error).not.toHaveBeenCalled();
  });
});
