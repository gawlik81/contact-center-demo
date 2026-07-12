import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { of, throwError } from 'rxjs';
import { CustomerCreateModalComponent } from './customer-create-modal.component';
import { CustomerService } from '../services/customer.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { CustomerResponse } from '../../../models/customer.model';

const mockCustomer: CustomerResponse = {
  customerId: 'cust-1',
  tenantId: 't1',
  firstName: 'Jan',
  lastName: 'Kowalski',
  externalId: null,
  phone: ['+48501234567'],
  email: [],
  customFields: {},
  gdprConsent: {},
  source: 'MANUAL',
  createdAt: '2026-01-01T08:00:00Z',
  updatedAt: '2026-01-01T08:00:00Z',
} as unknown as CustomerResponse;

describe('CustomerCreateModalComponent', () => {
  let fixture: ComponentFixture<CustomerCreateModalComponent>;
  let component: CustomerCreateModalComponent;

  let createCustomerSpy: ReturnType<typeof vi.fn>;
  let successSpy: ReturnType<typeof vi.fn>;
  let errorSpy: ReturnType<typeof vi.fn>;

  beforeAll(() => {
    // jsdom's <dialog> element does not implement showModal()/close() – stub them.
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

  beforeEach(async () => {
    createCustomerSpy = vi.fn().mockReturnValue(of(mockCustomer));
    successSpy = vi.fn();
    errorSpy = vi.fn();

    const customerServiceMock = {
      createCustomer: createCustomerSpy,
    } as unknown as CustomerService;

    const notificationServiceMock = {
      success: successSpy,
      error: errorSpy,
      warning: vi.fn(),
      info: vi.fn(),
    } as unknown as NotificationService;

    await TestBed.configureTestingModule({
      imports: [
        CustomerCreateModalComponent,
        TranslocoTestingModule.forRoot({
          langs: {
            pl: {
              supervisor: {
                customerCreate: {
                  title: 'Nowy klient',
                  errorCreate: 'Nie udało się utworzyć klienta.',
                  successCreate: 'Klient został dodany.',
                },
              },
              common: { cancel: 'Anuluj', saving: 'Zapisywanie...', optional: 'opcjonalne' },
            },
          },
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        { provide: CustomerService, useValue: customerServiceMock },
        { provide: NotificationService, useValue: notificationServiceMock },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CustomerCreateModalComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('addCustomField pushes a new empty key/value group', () => {
    expect(component.customFieldsArray.length).toBe(0);

    component.addCustomField();

    expect(component.customFieldsArray.length).toBe(1);
    expect(component.customFieldsArray.at(0).value).toEqual({ key: '', value: '' });
  });

  it('removeCustomField removes the row at the given index', () => {
    component.addCustomField();
    component.addCustomField();
    component.customFieldsArray.at(0).patchValue({ key: 'vip', value: 'true' });
    component.customFieldsArray.at(1).patchValue({ key: 'segment', value: 'gold' });

    component.removeCustomField(0);

    expect(component.customFieldsArray.length).toBe(1);
    expect(component.customFieldsArray.at(0).value).toEqual({ key: 'segment', value: 'gold' });
  });

  it('open() clears any leftover custom field rows left over from a previous session', () => {
    component.addCustomField();
    expect(component.customFieldsArray.length).toBe(1);

    component.open();

    expect(component.customFieldsArray.length).toBe(0);
  });

  it('onSubmit sends customFields and gdprConsent built from the form', () => {
    component.open();
    component.form.patchValue({
      firstName: 'Anna',
      lastName: 'Nowak',
      phones: '+48501234567',
      emails: '',
    });
    component.addCustomField();
    component.customFieldsArray.at(0).patchValue({ key: 'vip', value: 'true' });
    component.form.get('gdprConsent')?.patchValue({
      consent_given: true,
      marketing_consent: false,
    });

    component.onSubmit();

    expect(createCustomerSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        firstName: 'Anna',
        lastName: 'Nowak',
        phone: ['+48501234567'],
        email: [],
        customFields: { vip: 'true' },
        gdprConsent: { consent_given: true, marketing_consent: false },
      }),
    );
    expect(successSpy).toHaveBeenCalled();
  });

  it('onSubmit filters out custom fields with an empty key and trims values', () => {
    component.open();
    component.addCustomField();
    component.addCustomField();
    component.customFieldsArray.at(0).patchValue({ key: '  ', value: 'ignored' });
    component.customFieldsArray.at(1).patchValue({ key: ' segment ', value: ' gold ' });

    component.onSubmit();

    expect(createCustomerSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        customFields: { segment: 'gold' },
      }),
    );
  });

  it('onSubmit always sends gdprConsent even when both consents are false', () => {
    component.open();
    component.form.get('gdprConsent')?.patchValue({
      consent_given: false,
      marketing_consent: false,
    });

    component.onSubmit();

    expect(createCustomerSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        gdprConsent: { consent_given: false, marketing_consent: false },
      }),
    );
  });

  it('shows an error notification when creation fails', () => {
    createCustomerSpy.mockReturnValue(throwError(() => ({ status: 500 })));
    component.open();

    component.onSubmit();

    expect(errorSpy).toHaveBeenCalled();
    expect(successSpy).not.toHaveBeenCalled();
  });
});
