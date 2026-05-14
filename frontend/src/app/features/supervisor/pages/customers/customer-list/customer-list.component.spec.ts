import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { of, throwError } from 'rxjs';
import { CustomerListComponent } from './customer-list.component';
import { CustomerService } from '../services/customer.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { CustomerResponse, PagedResponse } from '../../../models/customer.model';

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

describe('CustomerListComponent', () => {
  let fixture: ComponentFixture<CustomerListComponent>;
  let component: CustomerListComponent;

  let getCustomersSpy: ReturnType<typeof vi.fn>;
  let deleteCustomerSpy: ReturnType<typeof vi.fn>;
  let successSpy: ReturnType<typeof vi.fn>;
  let errorSpy: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    getCustomersSpy = vi.fn().mockReturnValue(of(mockPage));
    deleteCustomerSpy = vi.fn().mockReturnValue(of(undefined));
    successSpy = vi.fn();
    errorSpy = vi.fn();

    const customerServiceMock = {
      getCustomers: getCustomersSpy,
      deleteCustomer: deleteCustomerSpy,
    } as unknown as CustomerService;

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
                  successAnonymize: 'Dane klienta zostały zanonimizowane.',
                  errorAnonymize:
                    'Nie udało się zanonimizować danych klienta. Spróbuj ponownie.',
                },
              },
              common: { sortAsc: 'Najwcześniejsze', sortDesc: 'Najpóźniejsze' },
            },
          },
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        provideRouter([]),
        { provide: CustomerService, useValue: customerServiceMock },
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

  it('openDeleteModal – sets selectedCustomer and showDeleteModal', () => {
    component.openDeleteModal(mockCustomer);
    expect(component.selectedCustomer()).toBe(mockCustomer);
    expect(component.showDeleteModal()).toBe(true);
  });

  it('closeDeleteModal – clears state', () => {
    component.openDeleteModal(mockCustomer);
    component.closeDeleteModal();
    expect(component.selectedCustomer()).toBeNull();
    expect(component.showDeleteModal()).toBe(false);
  });

  it('onDeleteConfirmed – calls deleteCustomer and shows success notification', () => {
    component.openDeleteModal(mockCustomer);
    component.onDeleteConfirmed();
    fixture.detectChanges();
    expect(deleteCustomerSpy).toHaveBeenCalledWith('c1');
    expect(successSpy).toHaveBeenCalledWith('Dane klienta zostały zanonimizowane.');
    expect(component.showDeleteModal()).toBe(false);
  });

  it('onDeleteConfirmed – shows error notification on failure', () => {
    deleteCustomerSpy.mockReturnValue(throwError(() => new Error('Server error')));
    component.openDeleteModal(mockCustomer);
    component.onDeleteConfirmed();
    fixture.detectChanges();
    expect(errorSpy).toHaveBeenCalledWith(
      'Nie udało się zanonimizować danych klienta. Spróbuj ponownie.',
    );
  });

  it('onDeleteConfirmed – does nothing when no customer selected', () => {
    component.onDeleteConfirmed();
    expect(deleteCustomerSpy).not.toHaveBeenCalled();
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
