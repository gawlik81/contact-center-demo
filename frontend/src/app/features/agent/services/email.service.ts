import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { PagedResponse } from '../../../core/models/paged-response.model';

export interface EmailMessage {
  id: string;
  messageIdHeader: string;
  threadRootMessageId: string;
  fromAddress: string;
  toAddresses: string[];
  ccAddresses: string[];
  subject: string;
  bodyHtml: string;
  bodyText?: string;
  direction: 'INBOUND' | 'OUTBOUND';
  status?: string;
  createdAt: string;
  receivedAt?: string;
  sentAt?: string;
  contactId?: string;
}

export interface EmailThread {
  messages: EmailMessage[];
  totalElements: number;
  hasMore: boolean;
}

export interface EmailTemplate {
  id: string;
  name: string;
  subjectTemplate: string;
  bodyHtml: string;
  variables: string[];
}

export interface SendReplyRequest {
  bodyHtml: string;
  subject: string;
  templateId?: string;
  templateVariables?: Record<string, string>;
}

@Injectable({ providedIn: 'root' })
export class EmailService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/email';

  getMessage(contactId: string): Observable<EmailMessage> {
    return this.http.get<EmailMessage>(`/api/email/contacts/${contactId}/message`);
  }

  getThread(
    threadRootMessageId: string,
    page: number,
    size: number,
  ): Observable<PagedResponse<EmailMessage>> {
    const params = new HttpParams()
      .set('page', page.toString())
      .set('size', size.toString());
    return this.http.get<PagedResponse<EmailMessage>>(
      `/api/email/threads/${encodeURIComponent(threadRootMessageId)}`,
      { params },
    );
  }

  sendReply(messageId: string, request: SendReplyRequest): Observable<EmailMessage> {
    return this.http.post<EmailMessage>(
      `${this.baseUrl}/messages/${messageId}/reply`,
      request,
    );
  }

  getTemplates(page: number, size: number): Observable<PagedResponse<EmailTemplate>> {
    const params = new HttpParams()
      .set('page', page.toString())
      .set('size', size.toString());
    return this.http.get<PagedResponse<EmailTemplate>>(`/api/email-templates`, { params });
  }

  previewTemplate(
    templateId: string,
    variables: Record<string, string>,
  ): Observable<{ subject: string; bodyHtml: string }> {
    return this.http.post<{ subject: string; bodyHtml: string }>(
      `/api/email-templates/${templateId}/preview`,
      { variables },
    );
  }
}
