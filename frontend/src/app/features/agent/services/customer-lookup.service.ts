import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, of, catchError } from 'rxjs';
import { tap } from 'rxjs/operators';
import { CustomerProfile } from '../../../core/models/customer-profile.model';
import { NotificationService } from '../../../core/services/notification.service';

interface CacheEntry {
  data: CustomerProfile | null;
  timestamp: number;
}

const CACHE_TTL_MS = 300_000; // 5 minutes

/**
 * Looks up customer data by phone number (CLI).
 * Calls GET /api/customers/lookup?phone={cli}  (BE-011)
 *
 * MSW mock note: until BE-011 is ready, intercept this URL in msw handlers
 * with a fixture returning CustomerProfile or 404 for unknown numbers.
 */
@Injectable({ providedIn: 'root' })
export class CustomerLookupService {
  private readonly http = inject(HttpClient);
  private readonly notifications = inject(NotificationService);

  private readonly cache = new Map<string, CacheEntry>();

  lookupByPhone(cli: string): Observable<CustomerProfile | null> {
    const normalised = cli.trim();
    if (!normalised) {
      return of(null);
    }

    const cached = this.cache.get(normalised);
    if (cached && Date.now() - cached.timestamp < CACHE_TTL_MS) {
      return of(cached.data);
    }

    return this.http
      .get<CustomerProfile>(`/api/customers/lookup`, { params: { phone: normalised } })
      .pipe(
        tap((profile) => this.setCache(normalised, profile)),
        catchError((err: HttpErrorResponse) => {
          if (err.status === 404) {
            this.setCache(normalised, null);
            return of(null);
          }
          this.notifications.error('Nie udalo sie pobrac danych klienta.');
          return of(null);
        }),
      );
  }

  /** Evict a cached entry (e.g. after creating a new customer profile). */
  evict(cli: string): void {
    this.cache.delete(cli.trim());
  }

  private setCache(cli: string, data: CustomerProfile | null): void {
    this.cache.set(cli, { data, timestamp: Date.now() });
  }
}
