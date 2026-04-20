import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  SocialMessage,
  SocialMessagesPage,
  SendSocialMessageRequest,
} from '../models/social-message.model';

@Injectable({ providedIn: 'root' })
export class SocialContactService {
  private readonly http = inject(HttpClient);

  getMessages(contactId: string, page: number, size: number): Observable<SocialMessagesPage> {
    const params = new HttpParams().set('page', page.toString()).set('size', size.toString());
    return this.http.get<SocialMessagesPage>(`/api/contacts/${contactId}/social/messages`, {
      params,
    });
  }

  sendMessage(contactId: string, request: SendSocialMessageRequest): Observable<SocialMessage> {
    return this.http.post<SocialMessage>(`/api/contacts/${contactId}/social/message`, request);
  }
}
