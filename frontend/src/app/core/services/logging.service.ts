import { inject, Injectable, isDevMode } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FrontendLogEntry, LogLevel } from '../models/frontend-log-entry.model';
import { AuthService } from './auth.service';
import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class LoggingService {
  private readonly http = inject(HttpClient);
  private readonly authService = inject(AuthService);

  private readonly queue: FrontendLogEntry[] = [];
  private readonly MAX_QUEUE_SIZE = 50;
  private readonly FLUSH_INTERVAL_MS = 10_000;
  private readonly BATCH_SIZE = 20;
  private isFlushing = false;

  constructor() {
    setInterval(() => this.flush(), this.FLUSH_INTERVAL_MS);

    window.addEventListener('beforeunload', () => {
      if (this.queue.length === 0) return;
      const batch = this.queue.slice(0, this.BATCH_SIZE);
      // keepalive: true ensures the request survives the page unload
      fetch(`${environment.apiUrl}/logs`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(batch),
        keepalive: true,
      });
    });
  }

  error(message: string, logger: string, error?: Error): void {
    console.error(`[${logger}]`, message, error ?? '');
    this.enqueue(this.buildEntry('ERROR', message, logger, error?.stack));
  }

  warn(message: string, logger: string): void {
    console.warn(`[${logger}]`, message);
    this.enqueue(this.buildEntry('WARN', message, logger));
  }

  info(message: string, logger: string): void {
    console.info(`[${logger}]`, message);
    this.enqueue(this.buildEntry('INFO', message, logger));
  }

  debug(message: string, logger: string): void {
    if (!isDevMode()) return;
    console.debug(`[${logger}]`, message);
    this.enqueue(this.buildEntry('DEBUG', message, logger));
  }

  private buildEntry(
    level: LogLevel,
    message: string,
    logger: string,
    stackTrace?: string,
  ): FrontendLogEntry {
    const tenantId = this.authService.currentTenantId() ?? undefined;
    const userId = this.authService.currentUserId() ?? undefined;
    return {
      level,
      message,
      logger,
      stackTrace,
      tenantId,
      userId,
      timestamp: Date.now(),
    };
  }

  private enqueue(entry: FrontendLogEntry): void {
    this.queue.push(entry);
    if (this.queue.length >= this.MAX_QUEUE_SIZE) {
      this.queue.shift();
    }
    if (entry.level === 'ERROR') {
      this.flush();
    }
  }

  private flush(): void {
    if (this.isFlushing || this.queue.length === 0) return;

    this.isFlushing = true;
    const batch = this.queue.slice(0, this.BATCH_SIZE);

    this.http
      .post(`${environment.apiUrl}/logs`, batch, {
        headers: { 'Content-Type': 'application/json' },
      })
      .subscribe({
        next: () => {
          this.queue.splice(0, batch.length);
          if (isDevMode()) {
            console.debug(`[LoggingService] Flushed ${batch.length} log entries.`);
          }
          this.isFlushing = false;
        },
        error: () => {
          // Leave entries in queue – next interval will retry.
          // Do NOT call any logging method here to avoid recursion.
          this.isFlushing = false;
        },
      });
  }
}
