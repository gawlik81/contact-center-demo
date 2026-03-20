import { ErrorHandler, inject, Injectable } from '@angular/core';
import { LoggingService } from './services/logging.service';

@Injectable()
export class AppErrorHandler implements ErrorHandler {
  private readonly loggingService = inject(LoggingService);

  handleError(error: unknown): void {
    const err = error instanceof Error ? error : new Error(String(error));
    this.loggingService.error(err.message || 'Unknown error', 'GlobalErrorHandler', err);
    // Also print to the console so developers see the full error in DevTools
    console.error('[AppErrorHandler]', error);
  }
}
