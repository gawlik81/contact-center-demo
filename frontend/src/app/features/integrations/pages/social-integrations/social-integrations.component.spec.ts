import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { Subject, of, throwError } from 'rxjs';
import { SocialIntegrationsComponent } from './social-integrations.component';
import { SocialIntegrationService } from '../../services/social-integration.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { SocialIntegration, WhatsAppConnectRequest } from '../../models/social-integration.model';

const mockConnectedIntegration: SocialIntegration = {
  integrationId: 'int-1',
  platform: 'WHATSAPP',
  pageId: 'page-1',
  displayName: 'My Business',
  webhookStatus: 'ACTIVE',
  tokenExpiresAt: null,
  webhookUrl: 'https://example.test/webhook',
  createdAt: '2026-01-01T08:00:00Z',
  updatedAt: '2026-01-01T08:00:00Z',
};

describe('SocialIntegrationsComponent - WhatsApp connect dialog', () => {
  let fixture: ComponentFixture<SocialIntegrationsComponent>;
  let component: SocialIntegrationsComponent;

  let getIntegrationsSpy: ReturnType<typeof vi.fn>;
  let connectWhatsAppSpy: ReturnType<typeof vi.fn>;
  let successSpy: ReturnType<typeof vi.fn>;
  let errorSpy: ReturnType<typeof vi.fn>;

  beforeAll(() => {
    // jsdom's <dialog> element does not implement showModal()/close() - stub them
    // the same way other dialog-based component specs in this repo do.
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
    getIntegrationsSpy = vi.fn().mockReturnValue(of([]));
    connectWhatsAppSpy = vi.fn().mockReturnValue(of(mockConnectedIntegration));
    successSpy = vi.fn();
    errorSpy = vi.fn();

    const integrationServiceMock = {
      getIntegrations: getIntegrationsSpy,
      initiateOAuth: vi.fn(),
      deleteIntegration: vi.fn(),
      connectWhatsApp: connectWhatsAppSpy,
    } as unknown as SocialIntegrationService;

    const notificationServiceMock = {
      success: successSpy,
      error: errorSpy,
      warning: vi.fn(),
      info: vi.fn(),
    } as unknown as NotificationService;

    await TestBed.configureTestingModule({
      imports: [
        SocialIntegrationsComponent,
        TranslocoTestingModule.forRoot({
          langs: {
            pl: {
              integrations: {
                social: {
                  title: 'Integracje społecznościowe',
                  pageDescription: 'Połącz platformy społecznościowe z centrum kontaktowym.',
                  refreshLabel: 'Odśwież',
                  connected: 'Połączono',
                  disconnected: 'Rozłączono',
                  connect: 'Połącz',
                  connecting: 'Łączenie...',
                  disconnect: 'Rozłącz',
                  disconnecting: 'Rozłączanie...',
                  errorLoad: 'Nie udało się wczytać integracji.',
                  errorConnect: 'Nie udało się połączyć konta WhatsApp.',
                  successConnect: 'Konto WhatsApp zostało połączone.',
                  errorDisconnect: 'Nie udało się rozłączyć integracji.',
                  successDisconnect: 'Integracja została rozłączona.',
                  confirmDisconnectTitle: 'Potwierdź rozłączenie',
                  whatsappConnectDialogTitle: 'Połącz WhatsApp Business',
                  whatsappNote:
                    'Potrzebujesz permanentnego tokenu dostępu wygenerowanego w Meta Business.',
                  whatsappCta: 'Przejdź do ustawień Meta Business',
                  phoneNumberIdLabel: 'ID numeru telefonu',
                  accessTokenLabel: 'Token dostępu',
                  accessTokenHint: 'Permanentny token dostępu z Meta Business.',
                  showTokenLabel: 'Pokaż token',
                  hideTokenLabel: 'Ukryj token',
                  displayNameLabel: 'Nazwa wyświetlana',
                  businessAccountIdLabel: 'ID konta biznesowego',
                  fieldRequired: 'To pole jest wymagane.',
                  fieldTooLong: 'Maksymalna długość to {{max}} znaków.',
                  pageName: 'Nazwa strony',
                  pageIdLabel: 'ID strony',
                  tokenExpires: 'Token wygasa',
                  webhookStatus: {
                    ACTIVE: 'Aktywny',
                    INACTIVE: 'Nieaktywny',
                    ERROR: 'Błąd',
                    EXPIRED_TOKEN: 'Token wygasł',
                  },
                },
              },
              common: { cancel: 'Anuluj', optional: 'opcjonalne', refresh: 'Odśwież' },
            },
          },
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        { provide: SocialIntegrationService, useValue: integrationServiceMock },
        { provide: NotificationService, useValue: notificationServiceMock },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(SocialIntegrationsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  /** The native <dialog> bound to #whatsappConnectDialogRef - same DOM node the component drives. */
  function whatsappDialogEl(): HTMLDialogElement {
    return fixture.nativeElement.querySelector('dialog.cc-dialog--form');
  }

  function mockSubmitEvent(): Event {
    return { preventDefault: vi.fn() } as unknown as Event;
  }

  function fillValidForm(): void {
    component.whatsappPhoneNumberId.set('  123456789  ');
    component.whatsappAccessToken.set('  EAAG-secret-token  ');
    component.whatsappDisplayName.set('  My Business  ');
    component.whatsappBusinessAccountId.set('  987654321  ');
  }

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  // 1. Form validation (whatsappFormValid computed signal)
  describe('whatsappFormValid', () => {
    it('is false when all required fields are empty', () => {
      expect(component.whatsappFormValid()).toBe(false);
    });

    it('is true when required fields are filled and the optional businessAccountId is empty', () => {
      component.whatsappPhoneNumberId.set('123456');
      component.whatsappAccessToken.set('token');
      component.whatsappDisplayName.set('My Business');
      component.whatsappBusinessAccountId.set('');

      expect(component.whatsappFormValid()).toBe(true);
    });

    it('is false when a required field contains only whitespace', () => {
      component.whatsappPhoneNumberId.set('   ');
      component.whatsappAccessToken.set('token');
      component.whatsappDisplayName.set('name');

      expect(component.whatsappFormValid()).toBe(false);
    });

    it('is false when phoneNumberId exceeds the max length', () => {
      component.whatsappPhoneNumberId.set('a'.repeat(component.maxFieldLength + 1));
      component.whatsappAccessToken.set('token');
      component.whatsappDisplayName.set('name');

      expect(component.whatsappFormValid()).toBe(false);
    });

    it('is false when displayName exceeds the max length', () => {
      component.whatsappPhoneNumberId.set('123');
      component.whatsappAccessToken.set('token');
      component.whatsappDisplayName.set('a'.repeat(component.maxFieldLength + 1));

      expect(component.whatsappFormValid()).toBe(false);
    });

    it('is false when the optional businessAccountId exceeds the max length', () => {
      component.whatsappPhoneNumberId.set('123');
      component.whatsappAccessToken.set('token');
      component.whatsappDisplayName.set('name');
      component.whatsappBusinessAccountId.set('a'.repeat(component.maxFieldLength + 1));

      expect(component.whatsappFormValid()).toBe(false);
    });

    it('is true when accessToken exceeds the max length - it has no backend length limit', () => {
      component.whatsappPhoneNumberId.set('123');
      component.whatsappAccessToken.set('a'.repeat(component.maxFieldLength * 4));
      component.whatsappDisplayName.set('name');

      expect(component.whatsappFormValid()).toBe(true);
    });

    it('is true at exactly the max length boundary for the limited fields', () => {
      component.whatsappPhoneNumberId.set('a'.repeat(component.maxFieldLength));
      component.whatsappAccessToken.set('token');
      component.whatsappDisplayName.set('a'.repeat(component.maxFieldLength));
      component.whatsappBusinessAccountId.set('a'.repeat(component.maxFieldLength));

      expect(component.whatsappFormValid()).toBe(true);
    });
  });

  // 2. Dialog open/close lifecycle
  describe('openWhatsappConnectDialog', () => {
    it('resets all form fields, the submitted flag and token visibility, then opens the dialog', () => {
      // dirty the state as if left over from a previous, cancelled session
      component.whatsappPhoneNumberId.set('old-phone');
      component.whatsappAccessToken.set('old-token');
      component.whatsappDisplayName.set('old-name');
      component.whatsappBusinessAccountId.set('old-baid');
      component.whatsappFormSubmitted.set(true);
      component.whatsappAccessTokenVisible.set(true);

      const showModalSpy = vi.spyOn(whatsappDialogEl(), 'showModal');

      component.openWhatsappConnectDialog();

      expect(component.whatsappPhoneNumberId()).toBe('');
      expect(component.whatsappAccessToken()).toBe('');
      expect(component.whatsappDisplayName()).toBe('');
      expect(component.whatsappBusinessAccountId()).toBe('');
      expect(component.whatsappFormSubmitted()).toBe(false);
      expect(component.whatsappAccessTokenVisible()).toBe(false);
      expect(showModalSpy).toHaveBeenCalledTimes(1);
    });
  });

  describe('closeWhatsappConnectDialog', () => {
    it('closes the dialog when no request is in flight', () => {
      const closeSpy = vi.spyOn(whatsappDialogEl(), 'close');
      component.connectingWhatsapp.set(false);

      component.closeWhatsappConnectDialog();

      expect(closeSpy).toHaveBeenCalledTimes(1);
    });

    it('does nothing while connectingWhatsapp is true - guards against closing mid-request', () => {
      const closeSpy = vi.spyOn(whatsappDialogEl(), 'close');
      component.connectingWhatsapp.set(true);

      component.closeWhatsappConnectDialog();

      expect(closeSpy).not.toHaveBeenCalled();
    });
  });

  describe('onWhatsappDialogCancel (native Escape key)', () => {
    it('prevents the dialog from closing while a request is in flight', () => {
      component.connectingWhatsapp.set(true);
      const event = mockSubmitEvent();

      component.onWhatsappDialogCancel(event);

      expect(event.preventDefault).toHaveBeenCalledTimes(1);
    });

    it('allows the native close when idle', () => {
      component.connectingWhatsapp.set(false);
      const event = mockSubmitEvent();

      component.onWhatsappDialogCancel(event);

      expect(event.preventDefault).not.toHaveBeenCalled();
    });
  });

  // 6. Reveal/hide access token
  describe('toggleWhatsappAccessTokenVisibility', () => {
    it('toggles visibility from false to true and back to false', () => {
      expect(component.whatsappAccessTokenVisible()).toBe(false);

      component.toggleWhatsappAccessTokenVisibility();
      expect(component.whatsappAccessTokenVisible()).toBe(true);

      component.toggleWhatsappAccessTokenVisibility();
      expect(component.whatsappAccessTokenVisible()).toBe(false);
    });
  });

  // 3, 4, 5, 7. Submit flow: validation gate, happy path, API error, concurrency guard
  describe('onWhatsappFormSubmit', () => {
    it('marks the form as submitted and does NOT call connectWhatsApp when the form is invalid', () => {
      // required fields left empty
      component.onWhatsappFormSubmit(mockSubmitEvent());

      expect(component.whatsappFormSubmitted()).toBe(true);
      expect(connectWhatsAppSpy).not.toHaveBeenCalled();
      expect(component.connectingWhatsapp()).toBe(false);
    });

    it('calls preventDefault on the submit event regardless of validity', () => {
      const event = mockSubmitEvent();

      component.onWhatsappFormSubmit(event);

      expect(event.preventDefault).toHaveBeenCalledTimes(1);
    });

    it('sends trimmed field values, including a trimmed businessAccountId when provided', () => {
      fillValidForm();

      component.onWhatsappFormSubmit(mockSubmitEvent());

      expect(connectWhatsAppSpy).toHaveBeenCalledTimes(1);
      expect(connectWhatsAppSpy).toHaveBeenCalledWith({
        phoneNumberId: '123456789',
        accessToken: 'EAAG-secret-token',
        displayName: 'My Business',
        businessAccountId: '987654321',
      } as WhatsAppConnectRequest);
    });

    it('omits businessAccountId (sends undefined) when it is left blank', () => {
      component.whatsappPhoneNumberId.set('123');
      component.whatsappAccessToken.set('token');
      component.whatsappDisplayName.set('name');
      component.whatsappBusinessAccountId.set('   ');

      component.onWhatsappFormSubmit(mockSubmitEvent());

      expect(connectWhatsAppSpy).toHaveBeenCalledWith(
        expect.objectContaining({ businessAccountId: undefined }),
      );
    });

    it('happy path: closes the dialog, shows a success toast, resets loading state and reloads integrations', () => {
      fillValidForm();
      const closeSpy = vi.spyOn(whatsappDialogEl(), 'close');
      getIntegrationsSpy.mockClear(); // ignore the initial ngOnInit call

      component.onWhatsappFormSubmit(mockSubmitEvent());

      expect(connectWhatsAppSpy).toHaveBeenCalledTimes(1);
      expect(component.connectingWhatsapp()).toBe(false);
      expect(closeSpy).toHaveBeenCalledTimes(1);
      expect(successSpy).toHaveBeenCalledTimes(1);
      expect(errorSpy).not.toHaveBeenCalled();
      expect(getIntegrationsSpy).toHaveBeenCalledTimes(1); // loadIntegrations() re-invoked
    });

    it('on API error: keeps the dialog open, resets connectingWhatsapp and shows an error toast', () => {
      connectWhatsAppSpy.mockReturnValue(throwError(() => new Error('boom')));
      fillValidForm();
      const closeSpy = vi.spyOn(whatsappDialogEl(), 'close');

      component.onWhatsappFormSubmit(mockSubmitEvent());

      expect(component.connectingWhatsapp()).toBe(false);
      expect(closeSpy).not.toHaveBeenCalled();
      expect(errorSpy).toHaveBeenCalledTimes(1);
      expect(successSpy).not.toHaveBeenCalled();
    });

    it('sets connectingWhatsapp to true while the request is pending, and blocks a concurrent submit', () => {
      const pending = new Subject<SocialIntegration>();
      connectWhatsAppSpy.mockReturnValue(pending.asObservable());
      fillValidForm();

      component.onWhatsappFormSubmit(mockSubmitEvent());

      expect(component.connectingWhatsapp()).toBe(true);
      expect(connectWhatsAppSpy).toHaveBeenCalledTimes(1);

      // A second submit attempt while the first request is still in flight must NOT
      // fire a second HTTP call - submitWhatsappConnect() guards on connectingWhatsapp().
      component.onWhatsappFormSubmit(mockSubmitEvent());
      expect(connectWhatsAppSpy).toHaveBeenCalledTimes(1);

      pending.next(mockConnectedIntegration);
      pending.complete();

      expect(component.connectingWhatsapp()).toBe(false);
    });
  });
});
