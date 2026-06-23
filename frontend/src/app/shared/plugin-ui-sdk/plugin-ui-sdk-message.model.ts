/**
 * Typy wiadomości `postMessage` wymienianych między pluginem (sandboxed iframe) i hostem
 * `cc-plugin-panel-host` (FE-099, EPIC-28, ARCHITECTURE.md §11.10/ADR-12).
 *
 * KONTRAKT USTALONY — musi być 1:1 zgodny z komentarzem na początku
 * `backend/app/src/main/resources/static/plugin-ui-sdk.js` (serwowanym publicznie pod
 * `/plugin-ui-sdk.js`, wstrzykiwanym przez deweloperów pluginów). Każda zmiana tutaj wymaga
 * równoległej zmiany w tamtym pliku.
 */

/** Typy żądań wysyłanych przez plugin do hosta (PLUGIN -> HOST). */
export type PluginUiSdkRequestType =
  | 'GET_CONTEXT'
  | 'INVOKE_MANUAL_ACTION'
  | 'REQUEST_RESIZE'
  | 'NOTIFY';

/** Typy odpowiedzi hosta korelowane przez `requestId` (HOST -> PLUGIN). */
export type PluginUiSdkResponseType = 'GET_CONTEXT_RESULT' | 'INVOKE_MANUAL_ACTION_RESULT';

/** Payload `INVOKE_MANUAL_ACTION` — identyfikator akcji z manifestu pluginu + parametry dowolne. */
export interface InvokeManualActionPayload {
  actionId: string;
  payload: unknown;
}

/** Payload `REQUEST_RESIZE` — żądana wysokość panelu w pikselach. */
export interface RequestResizePayload {
  height: number;
}

/** Severity zgodne 1:1 z `PluginUiSdk.notify(message, severity)` — bez `'success'`. */
export type PluginNotifySeverity = 'info' | 'warning' | 'error';

/** Payload `NOTIFY` — komunikat do wyświetlenia agentowi przez `NotificationService` hosta. */
export interface NotifyPayload {
  message: string;
  severity: PluginNotifySeverity;
}

/**
 * Wiadomość PLUGIN -> HOST. `requestId` jest obecny TYLKO dla `GET_CONTEXT` i
 * `INVOKE_MANUAL_ACTION` (wywołania z odpowiedzią); `REQUEST_RESIZE`/`NOTIFY` są
 * fire-and-forget i nie mają `requestId`.
 */
export interface PluginUiSdkRequest {
  type: PluginUiSdkRequestType;
  requestId?: string;
  payload?: unknown;
}

/**
 * Minimalny kontekst hosta zwracany dla `GET_CONTEXT` — celowo NIGDY pełny obiekt
 * klienta/kontaktu (kryterium akceptacji FE-099, ARCHITECTURE.md §11.10).
 */
export interface PluginContextPayload {
  tenantId: string;
  contactId: string | null;
  customerId: string | null;
}

/**
 * Wynik wywołania akcji manualnej, przezroczyście przekazany z `ManualActionResponseDto`
 * (BE-103) do pluginu jako payload `INVOKE_MANUAL_ACTION_RESULT`.
 */
export interface ManualActionResultPayload {
  success: boolean;
  resultData: Record<string, unknown>;
  message: string | null;
  error: string | null;
}

/**
 * Wiadomość HOST -> PLUGIN, korelowana przez `requestId` żądania. Wysyłana wyłącznie jako
 * reakcja na `GET_CONTEXT`/`INVOKE_MANUAL_ACTION` — `REQUEST_RESIZE`/`NOTIFY` nie mają odpowiedzi.
 */
export interface PluginUiSdkResponse {
  type: PluginUiSdkResponseType;
  requestId: string;
  success: boolean;
  payload?: PluginContextPayload | ManualActionResultPayload;
  error?: string;
}

const REQUEST_TYPES: readonly PluginUiSdkRequestType[] = [
  'GET_CONTEXT',
  'INVOKE_MANUAL_ACTION',
  'REQUEST_RESIZE',
  'NOTIFY',
];

/**
 * Type guard walidujący shape nieznanej wiadomości `MessageEvent.data` względem kontraktu
 * PLUGIN -> HOST PRZED wykonaniem jakiejkolwiek akcji (kryterium akceptacji FE-099). Nie ufa
 * niczemu poza `typeof`/`in` — wiadomość pochodzi z sandboxed iframe.
 */
export function isPluginUiSdkRequest(data: unknown): data is PluginUiSdkRequest {
  if (!data || typeof data !== 'object') {
    return false;
  }
  const candidate = data as Record<string, unknown>;
  if (typeof candidate['type'] !== 'string') {
    return false;
  }
  if (!REQUEST_TYPES.includes(candidate['type'] as PluginUiSdkRequestType)) {
    return false;
  }
  if ('requestId' in candidate && typeof candidate['requestId'] !== 'undefined') {
    if (typeof candidate['requestId'] !== 'string') {
      return false;
    }
  }
  return true;
}

/** Type guard dla payloadu `INVOKE_MANUAL_ACTION` (wymaga `actionId: string`). */
export function isInvokeManualActionPayload(
  payload: unknown,
): payload is InvokeManualActionPayload {
  if (!payload || typeof payload !== 'object') {
    return false;
  }
  return typeof (payload as Record<string, unknown>)['actionId'] === 'string';
}

/** Type guard dla payloadu `REQUEST_RESIZE` (wymaga `height: number`). */
export function isRequestResizePayload(payload: unknown): payload is RequestResizePayload {
  if (!payload || typeof payload !== 'object') {
    return false;
  }
  return typeof (payload as Record<string, unknown>)['height'] === 'number';
}

/** Type guard dla payloadu `NOTIFY` (wymaga `message: string` i poprawne `severity`). */
export function isNotifyPayload(payload: unknown): payload is NotifyPayload {
  if (!payload || typeof payload !== 'object') {
    return false;
  }
  const candidate = payload as Record<string, unknown>;
  return (
    typeof candidate['message'] === 'string' &&
    (candidate['severity'] === 'info' ||
      candidate['severity'] === 'warning' ||
      candidate['severity'] === 'error')
  );
}
