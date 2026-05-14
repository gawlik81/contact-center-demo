import { ErrorHandler, inject, Injectable } from '@angular/core';
import { LoggingService } from './services/logging.service';

/** Detects a chunk-load failure caused by a stale HTML referencing old build hashes. */
function isChunkLoadError(error: unknown): boolean {
  const msg =
    error instanceof Error ? error.message : typeof error === 'string' ? error : String(error);
  return (
    msg.includes('Failed to fetch dynamically imported module') ||
    msg.includes('ChunkLoadError') ||
    msg.includes('Importing a module script failed') ||
    // Vite / esbuild output
    /Loading chunk .+ failed/.test(msg)
  );
}

@Injectable()
export class AppErrorHandler implements ErrorHandler {
  private readonly loggingService = inject(LoggingService);

  handleError(error: unknown): void {
    const err = error instanceof Error ? error : new Error(String(error));

    if (isChunkLoadError(error)) {
      // A stale chunk hash means the app was redeployed while the user had the old
      // index.html open.  A hard reload fetches the new index.html and fresh chunks.
      this.loggingService.error(
        'Chunk load failed — reloading to pick up new build',
        'GlobalErrorHandler',
        err,
      );
      window.location.reload();
      return;
    }

    this.loggingService.error(err.message || 'Unknown error', 'GlobalErrorHandler', err);
    // Also print to the console so developers see the full error in DevTools
    console.error('[AppErrorHandler]', error);
  }
}
