import { AgentStatus } from './agent-status.model';
import { ContactType } from './contact-tab.model';
import { QueueItem } from './queue-item.model';

export type WsEventType =
  | 'CALL_INCOMING'
  | 'CALL_OUTBOUND'
  | 'CALL_HANGUP'
  | 'CALL_TRANSFER_CONSULT'
  | 'CALL_BRIDGE_COMPLETE'
  | 'CALL_CONSULT_CANCELLED'
  | 'CALL_CONSULT_ANSWERED'
  | 'AGENT_STATUS_CHANGED'
  | 'CONTACT_ASSIGNED'
  | 'QUEUE_UPDATE'
  | 'SUPERVISOR_METRICS'
  | 'SOCIAL_MESSAGE_RECEIVED'
  | 'PONG';

export interface WsEvent {
  eventType: WsEventType;
  payload: unknown;
}

export interface CallIncomingPayload {
  contactId: string;
  customerName: string;
  customerPhone: string;
  queueName: string;
}

/**
 * Payload dla eventu CALL_OUTBOUND (wychodzące połączenie kampanijne).
 *
 * Struktura identyczna z CallIncomingPayload — backend używa tego samego
 * CallIncomingPayload.from(callEvent). Pole customerPhone zawiera numer klienta
 * (pole `to` z CallEvent), queueName jest puste lub zawiera nazwę kampanii.
 */
export interface CallOutboundPayload {
  contactId: string;
  customerName: string;
  customerPhone: string;
  queueName: string;
}

/**
 * Payload dla eventu CALL_TRANSFER_CONSULT – druga noga attended transfer.
 *
 * Backend wysyła ten event do agenta docelowego (Agent2) gdy Agent1 inicjuje konsultację.
 * Agent2 powinien wyświetlić powiadomienie o nadchodzącej konsultacji z kontekstem klienta.
 */
export interface CallTransferConsultPayload {
  /** Twilio SID drugiej nogi (CA_...) – wymagany do bridge i hangup */
  secondLegCallId: string;
  /** UUID oryginalnego kontaktu (klienta) – do wyświetlenia historii */
  originalContactId: string;
  /** UUID agenta inicjującego konsultację */
  originatingAgentId: string;
  /** Numer telefonu klienta */
  customerPhone: string;
  /** Wyświetlana nazwa klienta */
  customerName: string;
}

/**
 * Payload dla eventu CALL_BRIDGE_COMPLETE – attended transfer bridge zakończony.
 * Agent2 powinien zaktualizować session.contactId i tab.contactId na newContactId.
 */
export interface CallBridgeCompletePayload {
  /** Twilio SID drugiej nogi (CA_...) – identyfikuje bieżącą sesję Agent2 */
  secondLegCallId: string;
  /** UUID nowego kontaktu Agent2 – do dyspozycji, historii, hangup */
  newContactId: string;
  /** Nazwa kolejki z której pochodzi połączenie (przepisana z oryginalnego kontaktu) */
  queueName: string;
}

/**
 * Payload dla eventu CALL_HANGUP – zakończenie połączenia.
 *
 * callId  – Twilio SID (CA_...) lub wewnętrzny identyfikator sesji
 * contactId – UUID rekordu kontaktu w DB (może być null gdy brak persistowanego rekordu)
 */
export interface CallHangupPayload {
  callId: string;
  contactId: string | null;
  agentId: string | null;
  from: string | null;
  to: string | null;
  eventType: string | null;
}

/**
 * Payload dla eventu CALL_CONSULT_ANSWERED – cel konsultacji (Agent2 lub numer zewnętrzny)
 * odebrał połączenie konsultacyjne.
 *
 * Wysyłany przez backend do Agent1 (inicjatora attended transfer) gdy druga noga
 * jest faktycznie odebrana. Dopiero po tym evencie Agent1 powinien widzieć aktywny
 * przycisk "Przekaż" (attendedConnected=true).
 *
 * Struktura identyczna z CallHangupPayload – backend używa tego samego CallPayload.
 */
export interface CallConsultAnsweredPayload {
  callId: string;
  contactId: string | null;
  agentId: string | null;
  from: string | null;
  to: string | null;
  eventType: string | null;
}

/**
 * Payload dla eventu CALL_CONSULT_CANCELLED – Agent1 anulował konsultację przed bridge.
 *
 * Wysyłany przez backend do Agent2 gdy Agent1 rozłączy konsultację (drugą nogę)
 * bez wykonania bridge. Agent2 powinien zamknąć sesję konsultacji i wrócić do
 * stanu AVAILABLE – bez ekranu ACW (dyspozycji), ponieważ żaden kontakt nie
 * został przypisany do Agent2.
 *
 * Struktura identyczna z CallHangupPayload – backend używa tego samego CallPayload.
 */
export interface CallConsultCancelledPayload {
  callId: string;
  contactId: string | null;
  agentId: string | null;
  from: string | null;
  to: string | null;
  eventType: string | null;
}

export interface ContactAssignedPayload {
  contactId: string;
  type: ContactType;
  customerName: string;
  customerIdentifier: string;
  queueName?: string;
  customerId?: string;
}

export interface AgentStatusChangedPayload {
  agentId: string;
  status: AgentStatus;
}

export interface QueueUpdatePayload {
  items: QueueItem[];
}
