import { Injectable, inject, signal } from '@angular/core';
import { Router, NavigationEnd, ActivatedRouteSnapshot } from '@angular/router';
import { filter } from 'rxjs/operators';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

export interface BreadcrumbItem {
  label: string;
  url: string;
}

@Injectable({ providedIn: 'root' })
export class BreadcrumbService {
  private readonly router = inject(Router);

  private readonly _breadcrumbs = signal<BreadcrumbItem[]>([]);
  readonly breadcrumbs = this._breadcrumbs.asReadonly();

  constructor() {
    this.router.events
      .pipe(
        filter((event) => event instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe(() => {
        const root = this.router.routerState.snapshot.root;
        this._breadcrumbs.set(this.buildBreadcrumbs(root));
      });
  }

  private buildBreadcrumbs(
    route: ActivatedRouteSnapshot | null,
    url = '',
    breadcrumbs: BreadcrumbItem[] = [],
  ): BreadcrumbItem[] {
    if (!route) return breadcrumbs;

    const pathSegments = route.url.map((seg) => seg.path).filter(Boolean);
    const currentUrl = pathSegments.length ? `${url}/${pathSegments.join('/')}` : url;

    // The flag hides breadcrumbs for the whole subtree: it is checked while descending, so any
    // crumbs collected from ancestors are discarded too.
    if (route.data?.['hideBreadcrumbs']) return [];

    const label: string | undefined = route.data?.['breadcrumb'];
    if (label) {
      breadcrumbs.push({ label, url: currentUrl });
    }

    if (route.firstChild) {
      return this.buildBreadcrumbs(route.firstChild, currentUrl, breadcrumbs);
    }

    return breadcrumbs;
  }
}
