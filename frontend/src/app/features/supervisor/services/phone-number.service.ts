import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import {
  CreatePhoneNumberRequest,
  CreateRoutingRuleRequest,
  PhoneNumber,
  PhoneRoutingRule,
  UpdatePhoneNumberRequest,
  UpdateRoutingRuleRequest,
} from '../models/phone-number.model';

@Injectable({ providedIn: 'root' })
export class PhoneNumberService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/phone-numbers`;

  listPhoneNumbers(): Observable<PhoneNumber[]> {
    return this.http.get<PhoneNumber[]>(this.baseUrl);
  }

  createPhoneNumber(req: CreatePhoneNumberRequest): Observable<PhoneNumber> {
    return this.http.post<PhoneNumber>(this.baseUrl, req);
  }

  updatePhoneNumber(id: string, req: UpdatePhoneNumberRequest): Observable<PhoneNumber> {
    return this.http.put<PhoneNumber>(`${this.baseUrl}/${id}`, req);
  }

  deletePhoneNumber(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  listRoutingRules(phoneNumberId: string): Observable<PhoneRoutingRule[]> {
    return this.http.get<PhoneRoutingRule[]>(`${this.baseUrl}/${phoneNumberId}/routing-rules`);
  }

  createRoutingRule(
    phoneNumberId: string,
    req: CreateRoutingRuleRequest,
  ): Observable<PhoneRoutingRule> {
    return this.http.post<PhoneRoutingRule>(`${this.baseUrl}/${phoneNumberId}/routing-rules`, req);
  }

  updateRoutingRule(
    phoneNumberId: string,
    ruleId: string,
    req: UpdateRoutingRuleRequest,
  ): Observable<PhoneRoutingRule> {
    return this.http.patch<PhoneRoutingRule>(
      `${this.baseUrl}/${phoneNumberId}/routing-rules/${ruleId}`,
      req,
    );
  }

  deleteRoutingRule(phoneNumberId: string, ruleId: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${phoneNumberId}/routing-rules/${ruleId}`);
  }
}
