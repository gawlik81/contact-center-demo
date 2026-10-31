import { TestBed } from '@angular/core/testing';
import { Component } from '@angular/core';
import { Router, provideRouter } from '@angular/router';
import { BreadcrumbService } from './breadcrumb.service';

@Component({ template: '' })
class DummyComponent {}

describe('BreadcrumbService', () => {
  let router: Router;
  let service: BreadcrumbService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          {
            path: 'agent',
            data: { breadcrumb: 'role.agent' },
            children: [
              {
                path: 'desktop',
                component: DummyComponent,
                data: { breadcrumb: 'nav.desktop', hideBreadcrumbs: true },
              },
              {
                path: 'wrap',
                data: { breadcrumb: 'nav.reports', hideBreadcrumbs: true },
                children: [
                  {
                    path: 'deep',
                    component: DummyComponent,
                    data: { breadcrumb: 'nav.customers' },
                  },
                ],
              },
              {
                path: 'customers',
                component: DummyComponent,
                data: { breadcrumb: 'nav.customers' },
              },
            ],
          },
        ]),
      ],
    });
    router = TestBed.inject(Router);
    service = TestBed.inject(BreadcrumbService);
  });

  it('hides breadcrumbs on routes flagged with hideBreadcrumbs', async () => {
    await router.navigateByUrl('/agent/desktop');
    expect(service.breadcrumbs()).toEqual([]);
  });

  it('keeps breadcrumbs on multi-level routes', async () => {
    await router.navigateByUrl('/agent/customers');
    expect(service.breadcrumbs().map((b) => b.label)).toEqual(['role.agent', 'nav.customers']);
  });

  it('hides breadcrumbs for the whole subtree when a parent route sets hideBreadcrumbs', async () => {
    await router.navigateByUrl('/agent/wrap/deep');
    expect(service.breadcrumbs()).toEqual([]);
  });

  it('restores breadcrumbs after leaving a hidden subtree', async () => {
    await router.navigateByUrl('/agent/wrap/deep');
    await router.navigateByUrl('/agent/customers');
    expect(service.breadcrumbs().map((b) => b.label)).toEqual(['role.agent', 'nav.customers']);
  });
});
