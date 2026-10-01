import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { of, throwError } from 'rxjs';
import { CustomerListComponent } from './customer-list.component';
import { CustomerService } from '../services/customer.service';
import { GdprService } from '../services/gdpr.service';
import { GdprAnonymizeModalComponent } from '../gdpr-anonymize-modal/gdpr-anonymize-modal.component';
import { NotificationService } from '../../../../../core/services/notification.service';
import { CustomerResponse, PagedResponse } from '../../../models/customer.model';
import { AnonymizePreviewResponse } from '../gdpr.model';

const mockCustomer: CustomerResponse = {
  customerId: 'c1',
  tenantId: 't1',
  firstName: 'Anna',
  lastName: 'Nowak',
  phone: ['48500600700'],
  email: ['anna@example.com'],
  customFields: {},
  gdprConsent: { consent_given: true },
  source: 'MANUAL',
  isDeleted: false,
  createdAt: '2025-03-01T08:00:00Z',
};

const mockPage: PagedResponse<CustomerResponse> = {
  content: [mockCustomer],
  totalElements: 1,
  totalPages: 1,
  page: 0,
  size: 20,
};

const mockPreview: AnonymizePreviewResponse = {
  dryRun: true,
  counts: { customer: 1, contact: 3 },
  matchedByLink: 3,
  matchedByIdentifier: 0,
  s3ObjectsToDelete: 0,
};

// jsdom (v28, bundled with this project) does not implement HTMLDialogElement.showModal/close —
// polyfill both so ngAfterViewInit's dialog.showModal() call does not throw during CD.
beforeAll(() => {
  if (!HTMLDialogElement.prototype.showModal) {
    HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
  }
  if (!HTMLDialogElement.prototype.close) {
    HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) {
      this.removeAttribute('open');
    };
  }
});

describe('CustomerListComponent', () => {
  let fixture: ComponentFixture<CustomerListComponent>;
  let component: CustomerListComponent;

  let getCustomersSpy: ReturnType<typeof vi.fn>;
  let previewAnonymizeSpy: ReturnType<typeof vi.fn>;
  let anonymizeSpy: ReturnType<typeof vi.fn>;
  let successSpy: ReturnType<typeof vi.fn>;
  let errorSpy: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    getCustomersSpy = vi.fn().mockReturnValue(of(mockPage));
    previewAnonymizeSpy = vi.fn().mockReturnValue(of(mockPreview));
    anonymizeSpy = vi.fn().mockReturnValue(of(undefined));
    successSpy = vi.fn();
    errorSpy = vi.fn();

    const customerServiceMock = {
      getCustomers: getCustomersSpy,
    } as unknown as CustomerService;

    const gdprServiceMock = {
      previewAnonymize: previewAnonymizeSpy,
      anonymize: anonymizeSpy,
    } as unknown as GdprService;

    const notificationServiceMock = {
      success: successSpy,
      error: errorSpy,
      warning: vi.fn(),
      info: vi.fn(),
    } as unknown as NotificationService;

    await TestBed.configureTestingModule({
      imports: [
        CustomerListComponent,
        TranslocoTestingModule.forRoot({
          langs: {
            pl: {
              supervisor: {
                customers: { errorLoad: 'Nie udało się pobrać listy klientów.' },
                gdprAnonymize: {
                  title: 'Anonimizacja GDPR',
                  message: 'Ta operacja jest nieodwracalna. Wszystkie dane osobowe klienta',
                  messageEnd: 'zostaną trwale zanonimizowane.',
                  effectsLabel: 'Skutki anonimizacji',
                  effect1: 'Imię i nazwisko zostaną zastąpione wartością ANONYMIZED',
                  effect2: 'Wszystkie numery telefonów zostaną usunięte',
                  effect3: 'Wszystkie adresy e-mail zostaną usunięte',
                  effect4: 'Historia kontaktów zostanie zachowana bez danych osobowych',
                  effect5: 'Wiadomości e-mail i social media zostaną usunięte',
                  effect6: 'Zaplanowane oddzwonienia zostaną zanonimizowane',
                  effect7: 'Rekordy kampanii zostaną zanonimizowane',
                  effect8: 'Transkrypcje i podsumowania AI zostaną usunięte',
                  effect9: 'Nagrania i załączniki zostaną usunięte',
                  previewTitle: 'Podgląd zakresu anonimizacji',
                  previewLoading: 'Ładowanie podglądu zakresu…',
                  previewError: 'Nie udało się załadować podglądu.',
                  previewRetry: 'Spróbuj ponownie',
                  previewCountsLabel: 'Liczba rekordów',
                  previewMatchedByLink: 'Dopasowane po powiązaniu',
                  previewMatchedByIdentifier: 'Dopasowane po numerze/adresie',
                  previewIdentifierWarning: 'Dopasowano także rekordy po numerze/adresie.',
                  previewBlockedHint: 'Poczekaj na załadowanie podglądu.',
                  previewCount: {
                    customer: 'Profil klienta',
                    contact: 'Kontakty',
                  },
                  confirmLabel: 'Aby potwierdzić, wpisz',
                  confirmHint: 'Wpisz dokładnie: ANONIMIZUJ',
                  confirmButton: 'Potwierdź anonimizację',
                  confirmButtonLabel: 'Potwierdź anonimizację danych klienta',
                  anonymizing: 'Anonimizowanie...',
                  successAnonymize: 'Dane klienta zostały zanonimizowane.',
                  errorAnonymize: 'Nie udało się zanonimizować danych klienta. Spróbuj ponownie.',
                },
              },
              common: { sortAsc: 'Najwcześniejsze', sortDesc: 'Najpóźniejsze', cancel: 'Anuluj' },
            },
          },
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        provideRouter([]),
        { provide: CustomerService, useValue: customerServiceMock },
        { provide: GdprService, useValue: gdprServiceMock },
        { provide: NotificationService, useValue: notificationServiceMock },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CustomerListComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should load customers on init', () => {
    expect(getCustomersSpy).toHaveBeenCalledWith({
      q: '',
      page: 0,
      size: 20,
      sort: 'createdAt,desc',
    });
    expect(component.customers().length).toBe(1);
    expect(component.totalElements()).toBe(1);
  });

  it('should call loadCustomers when loadCustomers() is invoked directly', () => {
    getCustomersSpy.mockReturnValue(of(mockPage));
    component.loadCustomers();
    expect(getCustomersSpy).toHaveBeenCalled();
    expect(component.customers().length).toBe(1);
  });

  it('should show error notification on load failure', () => {
    getCustomersSpy.mockReturnValue(throwError(() => new Error('Network error')));
    component.loadCustomers();
    fixture.detectChanges();
    expect(errorSpy).toHaveBeenCalledWith('Nie udało się pobrać listy klientów.');
    expect(component.customers()).toEqual([]);
  });

  it('getCustomerName – returns full name when both fields present', () => {
    expect(component.getCustomerName(mockCustomer)).toBe('Anna Nowak');
  });

  it('getCustomerName – falls back to phone when name is missing', () => {
    const c: CustomerResponse = { ...mockCustomer, firstName: undefined, lastName: undefined };
    expect(component.getCustomerName(c)).toBe('48500600700');
  });

  it('getCustomerName – falls back to email when no name and no phone', () => {
    const c: CustomerResponse = {
      ...mockCustomer,
      firstName: undefined,
      lastName: undefined,
      phone: [],
    };
    expect(component.getCustomerName(c)).toBe('anna@example.com');
  });

  it('getFirstPhone – returns first phone or dash', () => {
    expect(component.getFirstPhone(mockCustomer)).toBe('48500600700');
    expect(component.getFirstPhone({ ...mockCustomer, phone: [] })).toBe('—');
  });

  it('getFirstEmail – returns first email or dash', () => {
    expect(component.getFirstEmail(mockCustomer)).toBe('anna@example.com');
    expect(component.getFirstEmail({ ...mockCustomer, email: [] })).toBe('—');
  });

  it('getExternalId – returns externalId or dash', () => {
    expect(component.getExternalId({ ...mockCustomer, externalId: 'CRM-123' })).toBe('CRM-123');
    expect(component.getExternalId(mockCustomer)).toBe('—');
  });

  it('openAnonymizeModal – sets selectedCustomer and showAnonymizeModal', () => {
    component.openAnonymizeModal(mockCustomer);
    expect(component.selectedCustomer()).toBe(mockCustomer);
    expect(component.showAnonymizeModal()).toBe(true);
  });

  it('closeAnonymizeModal – clears state', () => {
    component.openAnonymizeModal(mockCustomer);
    component.closeAnonymizeModal();
    expect(component.selectedCustomer()).toBeNull();
    expect(component.showAnonymizeModal()).toBe(false);
  });

  it('onGdprAnonymizeConfirmed – closes modal and reloads customer list', () => {
    component.openAnonymizeModal(mockCustomer);
    getCustomersSpy.mockClear();
    component.onGdprAnonymizeConfirmed();
    expect(component.showAnonymizeModal()).toBe(false);
    expect(component.selectedCustomer()).toBeNull();
    expect(getCustomersSpy).toHaveBeenCalled();
  });

  describe('GDPR anonymize modal – unified with customer detail (FE-112)', () => {
    // These tests drive the REAL GdprAnonymizeModalComponent (not a stub) rendered by
    // customer-list, to prove the list uses the same modal/messages as customer-detail —
    // per AC: "akcja anonimizacji otwiera modal GDPR, sukces i błąd pokazują te same komunikaty".

    function openModalAndGetInstance(): GdprAnonymizeModalComponent {
      component.openAnonymizeModal(mockCustomer);
      fixture.detectChanges();
      const modalDebugEl = fixture.debugElement.query(By.directive(GdprAnonymizeModalComponent));
      expect(modalDebugEl).toBeTruthy();
      return modalDebugEl.componentInstance as GdprAnonymizeModalComponent;
    }

    it('row action opens the shared app-gdpr-anonymize-modal (not a customer-only delete modal)', () => {
      const modal = openModalAndGetInstance();
      expect(modal.customerId()).toBe('c1');
      expect(modal.customerName()).toBe('Anna Nowak');
      expect(previewAnonymizeSpy).toHaveBeenCalledWith('c1');
    });

    it('success – modal shows the same success message as customer detail, list reloads', () => {
      const modal = openModalAndGetInstance();
      expect(modal.previewState()).toBe('loaded');

      modal.confirmText.set('ANONIMIZUJ');
      getCustomersSpy.mockClear();
      modal.onConfirm();

      expect(anonymizeSpy).toHaveBeenCalledWith('c1');
      expect(successSpy).toHaveBeenCalledWith('Dane klienta zostały zanonimizowane.');

      fixture.detectChanges();
      expect(component.showAnonymizeModal()).toBe(false);
      expect(getCustomersSpy).toHaveBeenCalled();
    });

    it('error – modal shows the same error message as customer detail', () => {
      anonymizeSpy.mockReturnValue(throwError(() => new Error('Server error')));
      const modal = openModalAndGetInstance();

      modal.confirmText.set('ANONIMIZUJ');
      modal.onConfirm();

      expect(errorSpy).toHaveBeenCalledWith(
        'Nie udało się zanonimizować danych klienta. Spróbuj ponownie.',
      );
      // Modal stays open on error (parent's (confirmed) output not emitted).
      expect(component.showAnonymizeModal()).toBe(true);
    });

    it('preview error blocks confirmation from the list, same as customer detail', () => {
      previewAnonymizeSpy.mockReturnValue(throwError(() => new Error('timeout')));
      const modal = openModalAndGetInstance();

      expect(modal.previewState()).toBe('error');
      modal.confirmText.set('ANONIMIZUJ');
      expect(modal.isConfirmEnabled()).toBe(false);
    });
  });

  it('onSort – toggles direction on same field', () => {
    component.sortField.set('firstName');
    component.sortDir.set('asc');
    component.onSort('firstName');
    expect(component.sortDir()).toBe('desc');
    component.onSort('firstName');
    expect(component.sortDir()).toBe('asc');
  });

  it('onSort – resets to asc when switching to new field', () => {
    component.sortField.set('firstName');
    component.sortDir.set('desc');
    component.onSort('lastName');
    expect(component.sortField()).toBe('lastName');
    expect(component.sortDir()).toBe('asc');
  });

  it('onSort – resets currentPage to 0', () => {
    component.currentPage.set(3);
    component.onSort('firstName');
    expect(component.currentPage()).toBe(0);
  });

  it('pagination – onPrevPage decrements page', () => {
    component.totalPages.set(3);
    component.currentPage.set(2);
    component.onPrevPage();
    expect(component.currentPage()).toBe(1);
  });

  it('pagination – onNextPage increments page', () => {
    component.totalPages.set(3);
    component.currentPage.set(0);
    component.onNextPage();
    expect(component.currentPage()).toBe(1);
  });

  it('pagination – onPrevPage does not go below 0', () => {
    component.currentPage.set(0);
    component.onPrevPage();
    expect(component.currentPage()).toBe(0);
  });

  it('pagination – onNextPage does not exceed totalPages', () => {
    component.totalPages.set(2);
    component.currentPage.set(1);
    component.onNextPage();
    expect(component.currentPage()).toBe(1);
  });

  it('firstItemIndex / lastItemIndex calculations', () => {
    component.currentPage.set(1);
    component.totalElements.set(45);
    expect(component.firstItemIndex()).toBe(21);
    expect(component.lastItemIndex()).toBe(40);
  });

  it('lastItemIndex – does not exceed totalElements on last partial page', () => {
    component.currentPage.set(2);
    component.totalElements.set(55);
    expect(component.lastItemIndex()).toBe(55);
  });

  it('isSortActive – returns true for current sort field only', () => {
    component.sortField.set('lastName');
    expect(component.isSortActive('lastName')).toBe(true);
    expect(component.isSortActive('firstName')).toBe(false);
  });

  it('getSortAriaLabel – returns correct labels', () => {
    component.sortField.set('firstName');
    component.sortDir.set('asc');
    expect(component.getSortAriaLabel('firstName')).toBe('Najpóźniejsze');
    expect(component.getSortAriaLabel('lastName')).toBe('Najwcześniejsze');
    component.sortDir.set('desc');
    expect(component.getSortAriaLabel('firstName')).toBe('Najwcześniejsze');
  });
});
