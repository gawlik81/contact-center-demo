import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { environment } from '../../../../environments/environment';

export interface AiSummaryResponse {
  summary: string;
  modelUsed: string;
  tokensUsed: number;
}

export class AiConfigNotSetError extends Error {
  constructor() {
    super('Skonfiguruj dostawcę AI w ustawieniach supervisora.');
    this.name = 'AiConfigNotSetError';
  }
}

export class AiServiceUnavailableError extends Error {
  constructor() {
    super('Serwis AI tymczasowo niedostępny. Spróbuj ponownie.');
    this.name = 'AiServiceUnavailableError';
  }
}

@Injectable({ providedIn: 'root' })
export class AiSummaryService {
  private readonly http = inject(HttpClient);

  generateSummary(contactId: string): Observable<AiSummaryResponse> {
    return this.http
      .post<AiSummaryResponse>(`${environment.apiUrl}/contacts/${contactId}/ai-summary`, null)
      .pipe(
        catchError((err: HttpErrorResponse) => {
          if (err.status === 422) return throwError(() => new AiConfigNotSetError());
          if (err.status === 502) return throwError(() => new AiServiceUnavailableError());
          return throwError(() => err);
        }),
      );
  }
}
