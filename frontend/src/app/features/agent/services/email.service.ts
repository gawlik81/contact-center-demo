import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { PagedResponse } from '../../../core/models/paged-response.model';

export interface EmailAttachment {
  filename: string;
  contentType: string;
  sizeBytes: number;
  s3Key: string;
}

export interface PendingAttachment {
  s3Key: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
}

export interface UploadedAttachmentResponse {
  id: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
  s3Key: string;
}

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
  attachments?: EmailAttachment[];
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
  attachments?: PendingAttachment[];
}

export interface SendOutboundEmailRequest {
  toAddress: string;
  subject: string;
  bodyHtml: string;
  customerId?: string;
}

export interface AvailableVariable {
  key: string;
  category: string;
  labelPl: string;
  exampleValue: string;
}

export interface CreateTemplateRequest {
  name: string;
  subjectTemplate: string;
  bodyHtml: string;
  variables: string[];
}

export interface UpdateTemplateRequest {
  name?: string;
  subjectTemplate?: string;
  bodyHtml?: string;
  variables?: string[];
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
    const params = new HttpParams().set('page', page.toString()).set('size', size.toString());
    return this.http.get<PagedResponse<EmailMessage>>(
      `/api/email/threads/${encodeURIComponent(threadRootMessageId)}`,
      { params },
    );
  }

  sendReply(messageId: string, request: SendReplyRequest): Observable<EmailMessage> {
    return this.http.post<EmailMessage>(`${this.baseUrl}/messages/${messageId}/reply`, request);
  }

  getTemplates(page: number, size: number): Observable<PagedResponse<EmailTemplate>> {
    const params = new HttpParams().set('page', page.toString()).set('size', size.toString());
    return this.http.get<PagedResponse<EmailTemplate>>(`/api/email-templates`, { params });
  }

  previewTemplate(
    templateId: string,
    variables: Record<string, string>,
    contactId?: string,
  ): Observable<{ subject: string; bodyHtml: string }> {
    return this.http.post<{ subject: string; bodyHtml: string }>(
      `/api/email-templates/${templateId}/preview`,
      { variables, contactId: contactId ?? null },
    );
  }

  createTemplate(request: CreateTemplateRequest): Observable<EmailTemplate> {
    return this.http.post<EmailTemplate>(`/api/email-templates`, request);
  }

  updateTemplate(id: string, request: UpdateTemplateRequest): Observable<EmailTemplate> {
    return this.http.patch<EmailTemplate>(`/api/email-templates/${id}`, request);
  }

  deleteTemplate(id: string): Observable<void> {
    return this.http.delete<void>(`/api/email-templates/${id}`);
  }

  getAvailableVariables(): Observable<AvailableVariable[]> {
    return this.http.get<AvailableVariable[]>(`/api/email-templates/available-variables`);
  }

  sendOutbound(request: SendOutboundEmailRequest): Observable<EmailMessage> {
    return this.http.post<EmailMessage>(`${this.baseUrl}/messages/outbound`, request);
  }

  uploadAttachment(file: File): Observable<UploadedAttachmentResponse> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post<UploadedAttachmentResponse>(
      `${this.baseUrl}/attachments/upload`,
      formData,
    );
  }

  getAttachmentDownloadUrl(s3Key: string): Observable<{ url: string }> {
    const params = new HttpParams().set('s3Key', s3Key);
    return this.http.get<{ url: string }>(`${this.baseUrl}/attachments/download`, { params });
  }
}
