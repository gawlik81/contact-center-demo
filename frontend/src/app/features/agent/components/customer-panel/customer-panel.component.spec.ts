import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { Observable, of, throwError } from 'rxjs';
import pl from '../../../../../../public/i18n/pl.json';
import { AuthService } from '../../../../core/services/auth.service';
import { CustomerProfile } from '../../../../core/models/customer-profile.model';
import { CustomerLookupService } from '../../services/customer-lookup.service';
import { CustomerPanelComponent } from './customer-panel.component';

describe('CustomerPanelComponent error retry', () => {
  let fixture: ComponentFixture<CustomerPanelComponent>;
  const lookupByPhone = vi.fn<(cli: string) => Observable<CustomerProfile | null>>();

  const el = (selector: string) =>
    fixture.nativeElement.querySelector(selector) as HTMLElement | null;

  beforeEach(() => {
    vi.clearAllMocks();
    TestBed.configureTestingModule({
      imports: [
        TranslocoTestingModule.forRoot({
          langs: { pl } as never,
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        provideRouter([]),
        { provide: CustomerLookupService, useValue: { lookupByPhone, lookupByEmail: vi.fn() } },
        { provide: AuthService, useValue: { currentRole: () => 'AGENT' } },
      ],
    });
    fixture = TestBed.createComponent(CustomerPanelComponent);
  });

  it('offers a retry in an alert region and shows the customer after a successful retry', () => {
    lookupByPhone.mockReturnValueOnce(throwError(() => new Error('500')));
    lookupByPhone.mockReturnValueOnce(
      of({
        id: 'cu-1',
        firstName: 'Jan',
        lastName: 'Kowalski',
        phones: ['+48123456789'],
        emails: [],
        recentContacts: [],
      } satisfies CustomerProfile),
    );
    fixture.componentRef.setInput('cli', '+48123456789');
    fixture.detectChanges();

    const alert = el('.cp--error [role="alert"]');
    expect(alert?.textContent).toContain(pl.agent.customerPanel.errorText);
    const retry = el('.cp--error button') as HTMLButtonElement;
    expect(retry.textContent?.trim()).toBe(pl.agent.customerPanel.retry);

    retry.click();
    fixture.detectChanges();

    expect(lookupByPhone).toHaveBeenCalledTimes(2);
    expect(el('.cp--error')).toBeNull();
    expect(el('.cp--known')?.textContent).toContain('Jan Kowalski');
  });

  describe('focus after retry', () => {
    const profile = {
      id: 'cu-1',
      firstName: 'Jan',
      lastName: 'Kowalski',
      phones: ['+48123456789'],
      emails: [],
      recentContacts: [],
    } satisfies CustomerProfile;

    beforeEach(() => {
      document.body.appendChild(fixture.nativeElement);
      fixture.componentRef.setInput('cli', '+48123456789');
    });

    afterEach(() => fixture.nativeElement.remove());

    it('moves focus to the customer card after a successful retry', () => {
      lookupByPhone.mockReturnValueOnce(throwError(() => new Error('500')));
      lookupByPhone.mockReturnValueOnce(of(profile));
      fixture.detectChanges();

      (el('.cp--error button') as HTMLButtonElement).focus();
      (el('.cp--error button') as HTMLButtonElement).click();
      fixture.detectChanges();
      fixture.detectChanges();

      const card = el('.cp--known') as HTMLElement;
      expect(card.getAttribute('tabindex')).toBe('-1');
      expect(document.activeElement).toBe(card);
    });

    it('keeps focus on the retry button after a repeated failure', () => {
      lookupByPhone.mockReturnValue(throwError(() => new Error('500')));
      fixture.detectChanges();

      (el('.cp--error button') as HTMLButtonElement).click();
      fixture.detectChanges();
      fixture.detectChanges();

      expect(document.activeElement).toBe(el('.cp--error button'));
    });
  });
});

describe('CustomerPanelComponent supervisor CTAs (FE-134)', () => {
  const profile = {
    id: 'cu-1',
    firstName: 'Jan',
    lastName: 'Kowalski',
    phones: ['+48123456789'],
    emails: [],
    recentContacts: [],
  } satisfies CustomerProfile;

  function render(
    role: string,
    found: CustomerProfile | null,
  ): { root: HTMLElement; fixture: ComponentFixture<CustomerPanelComponent> } {
    TestBed.configureTestingModule({
      imports: [
        TranslocoTestingModule.forRoot({
          langs: { pl } as never,
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        provideRouter([]),
        {
          provide: CustomerLookupService,
          useValue: { lookupByPhone: () => of(found), lookupByEmail: vi.fn() },
        },
        { provide: AuthService, useValue: { currentRole: () => role } },
      ],
    });
    const fixture = TestBed.createComponent(CustomerPanelComponent);
    fixture.componentRef.setInput('cli', '+48123456789');
    fixture.detectChanges();
    return { root: fixture.nativeElement as HTMLElement, fixture };
  }

  it('does not render "Utwórz profil" for AGENT on an unknown number', () => {
    const { root } = render('AGENT', null);
    expect(root.querySelector('.cp--unknown')).not.toBeNull();
    expect(root.querySelector('.cp__cta-btn')).toBeNull();
    expect(root.textContent).not.toContain(pl.agent.customerPanel.createProfile);
  });

  it('does not render "Pełny profil" for AGENT on a known customer', () => {
    const { root } = render('AGENT', profile);
    expect(root.querySelector('.cp--known')).not.toBeNull();
    expect(root.querySelector('.cp__profile-link')).toBeNull();
  });

  it.each(['SUPERVISOR', 'ADMIN'])('renders "Utwórz profil" for %s', (role) => {
    const { root } = render(role, null);
    expect(root.querySelector('.cp__cta-btn')?.textContent).toContain(
      pl.agent.customerPanel.createProfile,
    );
  });

  it.each(['SUPERVISOR', 'ADMIN'])('renders "Pełny profil" for %s', (role) => {
    const { root } = render(role, profile);
    expect(root.querySelector('.cp__profile-link')?.textContent).toContain(
      pl.agent.customerPanel.viewProfile,
    );
  });
});
