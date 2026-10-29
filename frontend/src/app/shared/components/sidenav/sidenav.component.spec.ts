import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of } from 'rxjs';
import { AuthService } from '../../../core/services/auth.service';
import { AdminMetricsService } from '../../../features/admin/services/admin-metrics.service';
import { RetentionService } from '../../../features/supervisor/services/retention.service';
import { SidenavComponent } from './sidenav.component';

const KEY = 'cc_sidenav_collapsed_v2';
const LEGACY_KEY = 'cc_sidenav_collapsed';

describe('SidenavComponent collapsed default (FE-123)', () => {
  let role: ReturnType<typeof signal<string | null>>;

  function create(width: number): SidenavComponent {
    vi.stubGlobal('innerWidth', width);
    return TestBed.runInInjectionContext(() => new SidenavComponent());
  }

  beforeEach(() => {
    localStorage.clear();
    role = signal<string | null>('AGENT');
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: { currentRole: role } },
        { provide: AdminMetricsService, useValue: {} },
        { provide: RetentionService, useValue: { pendingDeletionCategoryCount$: of(0) } },
        {
          provide: Router,
          useValue: { url: '/agent/desktop', events: of(), isActive: () => false },
        },
      ],
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  it('starts as the icon rail for an agent on a laptop-width window', () => {
    expect(create(1334).isCollapsed()).toBe(true);
    expect(create(1440).isCollapsed()).toBe(true);
  });

  it('starts expanded for an agent on a wide window', () => {
    expect(create(1920).isCollapsed()).toBe(false);
  });

  it('keeps supervisor and admin expanded at laptop width (R2)', () => {
    role.set('SUPERVISOR');
    expect(create(1334).isCollapsed()).toBe(false);
    role.set('ADMIN');
    expect(create(1334).isCollapsed()).toBe(false);
  });

  it('a stored choice always wins over the derived default', () => {
    localStorage.setItem(KEY, 'false');
    expect(create(1334).isCollapsed()).toBe(false);
    localStorage.setItem(KEY, 'true');
    expect(create(1920).isCollapsed()).toBe(true);
  });

  it('does not freeze the derived default as a user choice, but stores a toggle', () => {
    const nav = create(1334);
    TestBed.tick();
    expect(localStorage.getItem(KEY)).toBeNull();
    nav.toggleCollapsed();
    expect(localStorage.getItem(KEY)).toBe('false');
  });

  it('ignores and removes the legacy key (it was written on every mount, also as false)', () => {
    localStorage.setItem(LEGACY_KEY, 'false');
    expect(create(1334).isCollapsed()).toBe(true); // agent rail default still applies
    expect(localStorage.getItem(LEGACY_KEY)).toBeNull();
    expect(localStorage.getItem(KEY)).toBeNull();
  });
});
