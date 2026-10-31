import { Signal, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { filter, map } from 'rxjs';

/** Reactive current URL (`router.url` itself is not a signal). Call in an injection context. */
export function currentUrlSignal(): Signal<string> {
  const router = inject(Router);
  return toSignal(
    router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      map(() => router.url),
    ),
    { initialValue: router.url },
  );
}
