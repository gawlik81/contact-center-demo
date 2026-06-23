import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
  OnInit,
  computed,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { catchError, of } from 'rxjs';
import { NotificationService } from '../../../core/services/notification.service';
import {
  ManualActionResultPayload,
  PluginContextPayload,
  PluginUiSdkRequest,
  PluginUiSdkResponse,
  isInvokeManualActionPayload,
  isNotifyPayload,
  isPluginUiSdkRequest,
  isRequestResizePayload,
} from '../../plugin-ui-sdk/plugin-ui-sdk-message.model';

/** Punkty montowania panelu pluginu (manifest `uiPanels[].mountPoint`, ARCHITECTURE.md §11). */
export type PluginPanelMountPoint =
  | 'AGENT_DESKTOP_SIDE_PANEL'
  | 'AGENT_DESKTOP_TOOLBAR'
  | 'SUPERVISOR_DASHBOARD';

/** Wysokość minimalna/maksymalna panelu pluginu (px) — clamp dla `REQUEST_RESIZE` (RT-11). */
const MIN_PANEL_HEIGHT_PX = 100;
const MAX_PANEL_HEIGHT_PX = 800;
const DEFAULT_PANEL_HEIGHT_PX = 300;

/**
 * Host renderujący panel UI pluginu w sandboxed iframe + implementujący stronę hosta
 * protokołu `postMessage` z `PluginUiSdk` (FE-099, EPIC-28, ARCHITECTURE.md §11.10/ADR-12).
 *
 * Bezpieczeństwo (RT-11 — XSS/cross-context escape przez iframe pluginu):
 * - `sandbox="allow-scripts allow-forms"` BEZ `allow-same-origin` — plugin nie może nigdy
 *   odczytać `document`/`localStorage` hosta, nawet jeśli treść panelu jest złośliwa.
 * - `[src]` przepuszczony przez `DomSanitizer.bypassSecurityTrustResourceUrl` TYLKO dla
 *   jednego, konkretnego URL-a zbudowanego z `installationId` (`/plugin-assets/{id}/index.html`)
 *   — nigdy generyczny bypass dla dowolnego inputu.
 * - `event.origin` walidowany na KAŻDEJ wiadomości przed jakimkolwiek przetwarzaniem.
 * - JWT agenta nigdy nie trafia do iframe — `INVOKE_MANUAL_ACTION` jest proxy'owane przez
 *   normalne wywołanie `HttpClient` hosta (JWT dodawany przez istniejący `HttpInterceptor`).
 */
@Component({
  selector: 'cc-plugin-panel-host',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [],
  templateUrl: './plugin-panel-host.component.html',
  styleUrl: './plugin-panel-host.component.scss',
})
export class PluginPanelHostComponent implements OnInit, OnDestroy {
  readonly installationId = input.required<string>();
  readonly mountPoint = input.required<PluginPanelMountPoint>();
  /**
   * Kontekst bieżącego tenanta/kontaktu/klienta przekazywany przez rodzica (np.
   * `AgentDesktopComponent`) — host NIE pobiera go samodzielnie z głębi serwisów (prostsze,
   * testowalne, mniej coupling). `contactId`/`customerId` są `null` w mountPointach bez
   * aktywnego kontaktu (np. `SUPERVISOR_DASHBOARD`). `tenantId` pochodzi z auth contextu
   * rodzica (JWT claim), nigdy hardkodowane.
   */
  readonly tenantId = input.required<string>();
  readonly contactId = input<string | null>(null);
  readonly customerId = input<string | null>(null);

  private readonly sanitizer = inject(DomSanitizer);
  private readonly http = inject(HttpClient);
  private readonly notifications = inject(NotificationService);

  private readonly iframeRef = viewChild<ElementRef<HTMLIFrameElement>>('iframeEl');

  /** Origina hosta — jedyna origina zaufana dla wiadomości przychodzących i odpowiedzi. */
  private readonly expectedOrigin = window.location.origin;

  private readonly messageListener = (event: MessageEvent): void => this.onMessage(event);

  readonly panelHeightPx = signal(DEFAULT_PANEL_HEIGHT_PX);

  readonly trustedPluginPanelUrl = computed<SafeResourceUrl>(() => {
    const url = `/plugin-assets/${encodeURIComponent(this.installationId())}/index.html`;
    return this.sanitizer.bypassSecurityTrustResourceUrl(url);
  });

  ngOnInit(): void {
    window.addEventListener('message', this.messageListener);
  }

  ngOnDestroy(): void {
    window.removeEventListener('message', this.messageListener);
  }

  onIframeLoad(): void {
    // Punkt rozszerzenia na przyszłość (np. telemetria czasu ładowania panelu) — celowo
    // bez logiki teraz, wymagany przez szablon zgodny z kontraktem FE-099.
  }

  private onMessage(event: MessageEvent): void {
    if (event.origin !== this.expectedOrigin) {
      console.warn(
        `[cc-plugin-panel-host] Odrzucono wiadomość z nieoczekiwanej originy: ${event.origin}`,
      );
      return;
    }

    const iframe = this.iframeRef()?.nativeElement;
    if (!iframe || event.source !== iframe.contentWindow) {
      console.warn(
        '[cc-plugin-panel-host] Odrzucono wiadomość z nieoczekiwanego źródła (nie panel pluginu).',
      );
      return;
    }

    const data = event.data;
    if (!isPluginUiSdkRequest(data)) {
      console.warn('[cc-plugin-panel-host] Odrzucono wiadomość o nieprawidłowym shape:', data);
      return;
    }

    switch (data.type) {
      case 'GET_CONTEXT':
        this.handleGetContext(data, iframe);
        break;
      case 'INVOKE_MANUAL_ACTION':
        this.handleInvokeManualAction(data, iframe);
        break;
      case 'REQUEST_RESIZE':
        this.handleRequestResize(data.payload);
        break;
      case 'NOTIFY':
        this.handleNotify(data.payload);
        break;
    }
  }

  private handleGetContext(request: PluginUiSdkRequest, iframe: HTMLIFrameElement): void {
    if (!request.requestId) {
      console.warn('[cc-plugin-panel-host] GET_CONTEXT bez requestId — zignorowano.');
      return;
    }
    const payload: PluginContextPayload = {
      tenantId: this.tenantId(),
      contactId: this.contactId(),
      customerId: this.customerId(),
    };
    this.respond(iframe, {
      type: 'GET_CONTEXT_RESULT',
      requestId: request.requestId,
      success: true,
      payload,
    });
  }

  private handleInvokeManualAction(request: PluginUiSdkRequest, iframe: HTMLIFrameElement): void {
    if (!request.requestId) {
      console.warn('[cc-plugin-panel-host] INVOKE_MANUAL_ACTION bez requestId — zignorowano.');
      return;
    }
    if (!isInvokeManualActionPayload(request.payload)) {
      console.warn(
        '[cc-plugin-panel-host] INVOKE_MANUAL_ACTION o nieprawidłowym payload:',
        request.payload,
      );
      this.respond(iframe, {
        type: 'INVOKE_MANUAL_ACTION_RESULT',
        requestId: request.requestId,
        success: false,
        error: 'Nieprawidłowy payload żądania (wymagane actionId).',
      });
      return;
    }

    const { actionId, payload } = request.payload;
    const requestId = request.requestId;

    this.http
      .post<ManualActionResultPayload>(
        `/api/agent/plugins/${this.installationId()}/manual-action/${actionId}`,
        {
          contactId: this.contactId(),
          customerId: this.customerId(),
          payload,
        },
      )
      .pipe(
        catchError((err: unknown) => {
          const message =
            err && typeof err === 'object' && 'message' in err
              ? String((err as { message: unknown }).message)
              : 'Wywołanie akcji nie powiodło się.';
          this.respond(iframe, {
            type: 'INVOKE_MANUAL_ACTION_RESULT',
            requestId,
            success: false,
            error: message,
          });
          return of(null);
        }),
      )
      .subscribe((result) => {
        if (result === null) {
          return;
        }
        this.respond(iframe, {
          type: 'INVOKE_MANUAL_ACTION_RESULT',
          requestId,
          success: true,
          payload: result,
        });
      });
  }

  private handleRequestResize(payload: unknown): void {
    if (!isRequestResizePayload(payload)) {
      console.warn('[cc-plugin-panel-host] REQUEST_RESIZE o nieprawidłowym payload:', payload);
      return;
    }
    const clamped = Math.min(Math.max(payload.height, MIN_PANEL_HEIGHT_PX), MAX_PANEL_HEIGHT_PX);
    this.panelHeightPx.set(clamped);
  }

  private handleNotify(payload: unknown): void {
    if (!isNotifyPayload(payload)) {
      console.warn('[cc-plugin-panel-host] NOTIFY o nieprawidłowym payload:', payload);
      return;
    }
    switch (payload.severity) {
      case 'warning':
        this.notifications.warning(payload.message);
        break;
      case 'error':
        this.notifications.error(payload.message);
        break;
      case 'info':
      default:
        this.notifications.info(payload.message);
        break;
    }
  }

  /** Wysyła odpowiedź TYLKO do `iframe.contentWindow`, z konkretną originą (nigdy `'*'`). */
  private respond(iframe: HTMLIFrameElement, response: PluginUiSdkResponse): void {
    iframe.contentWindow?.postMessage(response, this.expectedOrigin);
  }
}
