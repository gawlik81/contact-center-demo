/**
 * PluginUiSdk — skrypt SDK serwowany przez platformę (BE-107/FE-099, EPIC-28,
 * ARCHITECTURE.md §11.10) i wstrzykiwany przez deweloperów pluginów we własnym
 * `index.html`:
 *
 *   <script src="/plugin-ui-sdk.js"></script>
 *
 * Plik jest PUBLICZNY (bez JWT) i statyczny — identyczny dla każdego pluginu/tenanta.
 * Implementuje request/response protokół przez `window.parent.postMessage(...)`
 * z hostem Angular (`cc-plugin-panel-host`, FE-099). Plugin działa w sandboxowanym
 * `<iframe sandbox="allow-scripts allow-forms">` (BEZ `allow-same-origin`), więc to
 * jest JEDYNY kanał komunikacji z hostem — żaden fetch do `/api/**` z tego kontekstu
 * nie ma dostępu do JWT agenta.
 *
 * Skrypt jest celowo bez build stepu/transpilacji (ES2017+, brak importów/modułów) —
 * deweloper pluginu wczytuje go jako zwykły `<script>`, prawdopodobnie bez bundlera.
 *
 * ---------------------------------------------------------------------------
 * KONTRAKT WIADOMOŚCI postMessage (USTALONY, MUSI być zgodny z
 * frontend/src/app/shared/plugin-ui-sdk/plugin-ui-sdk-message.model.ts — FE-099)
 * ---------------------------------------------------------------------------
 *
 * Kierunek PLUGIN -> HOST (żądanie), wysyłane przez `window.parent.postMessage`:
 *
 *   {
 *     type: 'GET_CONTEXT' | 'INVOKE_MANUAL_ACTION' | 'REQUEST_RESIZE' | 'NOTIFY' | 'OPEN_URL',
 *     requestId?: string,   // obecny TYLKO dla GET_CONTEXT i INVOKE_MANUAL_ACTION
 *                            // (wywołania z odpowiedzią/Promise); unikalny per wywołanie
 *     payload?: unknown     // kształt zależny od `type`, patrz niżej
 *   }
 *
 *   Kształt `payload` per `type`:
 *     - GET_CONTEXT:           payload nieobecny (undefined)
 *     - INVOKE_MANUAL_ACTION:  payload = { actionId: string, payload: unknown }
 *     - REQUEST_RESIZE:        payload = { height: number }
 *     - NOTIFY:                payload = { message: string, severity: 'info' | 'warning' | 'error' }
 *     - OPEN_URL:              payload = { url: string }  // musi zaczynać się od https:// lub http://
 *
 *   `REQUEST_RESIZE`, `NOTIFY` i `OPEN_URL` są fire-and-forget — host NIE odsyła odpowiedzi,
 *   wywołania SDK dla nich nie zwracają Promise.
 *
 * Kierunek HOST -> PLUGIN (odpowiedź), tylko jako reakcja na GET_CONTEXT /
 * INVOKE_MANUAL_ACTION, korelowana przez `requestId`:
 *
 *   {
 *     type: 'GET_CONTEXT_RESULT' | 'INVOKE_MANUAL_ACTION_RESULT',
 *     requestId: string,     // musi odpowiadać requestId żądania
 *     success: boolean,
 *     payload?: unknown,     // obecny gdy success === true
 *                            // GET_CONTEXT_RESULT: { tenantId: string; contactId: string | null; customerId: string | null }
 *                            // INVOKE_MANUAL_ACTION_RESULT: ManualActionResult (przezroczyste przekazanie wyniku BE-103)
 *     error?: string         // obecny gdy success === false (komunikat błędu, bez stack trace)
 *   }
 *
 * Host waliduje `event.origin` przed przetworzeniem JAKIEJKOLWIEK wiadomości
 * (odpowiedzialność hosta, nie tego SDK) — patrz FE-099. Ten SDK nie waliduje
 * originy nadawcy odpowiedzi we własnym listenerze, bo zawsze nasłuchuje
 * wyłącznie na `window.parent` (jedyny możliwy nadawca w architekturze iframe);
 * host jest stroną odpowiedzialną za odrzucanie nieoczekiwanych originów.
 */
(function (window) {
    'use strict';

    /** Prefiks identyfikatorów żądań — ułatwia debugowanie w devtools (filtrowanie po prefiksie). */
    var REQUEST_ID_PREFIX = 'plugin-ui-sdk-';

    /** Mapa requestId -> { resolve, reject } dla wywołań oczekujących na odpowiedź hosta. */
    var pendingRequests = {};

    /** Licznik monotoniczny — wystarczający do unikalności w obrębie jednego dokumentu iframe. */
    var requestCounter = 0;

    function generateRequestId() {
        requestCounter += 1;
        return REQUEST_ID_PREFIX + Date.now() + '-' + requestCounter;
    }

    /**
     * Wysyła żądanie do hosta i zwraca Promise rozwiązywany/odrzucany po nadejściu
     * odpowiedzi z dopasowanym `requestId` (typ `<type>_RESULT`).
     */
    function sendRequest(type, payload) {
        return new Promise(function (resolve, reject) {
            var requestId = generateRequestId();
            pendingRequests[requestId] = { resolve: resolve, reject: reject };

            var message = { type: type, requestId: requestId };
            if (payload !== undefined) {
                message.payload = payload;
            }
            window.parent.postMessage(message, '*');
        });
    }

    /**
     * Wysyła wiadomość fire-and-forget (bez requestId, bez Promise) — REQUEST_RESIZE/NOTIFY.
     */
    function sendFireAndForget(type, payload) {
        var message = { type: type };
        if (payload !== undefined) {
            message.payload = payload;
        }
        window.parent.postMessage(message, '*');
    }

    /**
     * Listener odpowiedzi hosta — dopasowuje `requestId` do oczekującego wywołania
     * i rozwiązuje/odrzuca odpowiedni Promise. Wiadomości bez znanego `requestId`
     * (np. nieoczekiwane/spóźnione/z innego źródła) są ignorowane po cichu.
     */
    window.addEventListener('message', function (event) {
        var data = event.data;
        if (!data || typeof data !== 'object') {
            return;
        }
        if (data.type !== 'GET_CONTEXT_RESULT' && data.type !== 'INVOKE_MANUAL_ACTION_RESULT') {
            return;
        }

        var pending = pendingRequests[data.requestId];
        if (!pending) {
            return;
        }
        delete pendingRequests[data.requestId];

        if (data.success) {
            pending.resolve(data.payload);
        } else {
            pending.reject(new Error(data.error || 'PluginUiSdk: nieznany błąd hosta'));
        }
    });

    /**
     * Publiczne API SDK eksponowane pluginowi jako `window.PluginUiSdk`.
     */
    window.PluginUiSdk = {

        /**
         * Zwraca minimalny, nieukrywający danych osobowych kontekst hosta:
         * tenantId, contactId (bieżąca rozmowa, lub null) i customerId (lub null).
         * Nigdy pełny obiekt klienta/kontaktu (kryterium akceptacji FE-099).
         *
         * @returns {Promise<{tenantId: string, contactId: (string|null), customerId: (string|null)}>}
         */
        getContext: function () {
            return sendRequest('GET_CONTEXT');
        },

        /**
         * Wywołuje akcję manualną pluginu po stronie backendu (host wykonuje
         * REST call z JWT agenta — iframe nigdy nie ma dostępu do JWT).
         *
         * @param {string} actionId
         * @param {*} payload
         * @returns {Promise<*>} ManualActionResult zwrócony przez backend (BE-103)
         */
        invokeManualAction: function (actionId, payload) {
            return sendRequest('INVOKE_MANUAL_ACTION', { actionId: actionId, payload: payload });
        },

        /**
         * Informuje host o żądanej wysokości panelu (fire-and-forget, bez odpowiedzi).
         *
         * @param {number} height wysokość w pikselach
         */
        requestResize: function (height) {
            sendFireAndForget('REQUEST_RESIZE', { height: height });
        },

        /**
         * Wyświetla komunikat (toast) po stronie hosta (fire-and-forget, bez odpowiedzi).
         *
         * @param {string} message
         * @param {'info'|'warning'|'error'} severity
         */
        notify: function (message, severity) {
            sendFireAndForget('NOTIFY', { message: message, severity: severity });
        },

        /**
         * Żąda od hosta otwarcia podanego URL-a w nowej zakładce przeglądarki
         * (fire-and-forget — host nie odsyła odpowiedzi). URL musi zaczynać się od
         * https:// lub http://; inne schematy są odrzucane przez walidację hosta.
         *
         * @param {string} url URL do otwarcia
         */
        openUrl: function (url) {
            sendFireAndForget('OPEN_URL', { url: url });
        }
    };

})(window);
