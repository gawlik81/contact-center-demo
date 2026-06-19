import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { EmailService } from './email.service';

describe('EmailService', () => {
  let service: EmailService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), EmailService],
    });

    service = TestBed.inject(EmailService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  describe('previewTemplate', () => {
    it('should send customerId in request body when provided', () => {
      service.previewTemplate('tpl-1', { firstName: '' }, undefined, 'cust-123').subscribe();

      const req = httpMock.expectOne('/api/email-templates/tpl-1/preview');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({
        variables: { firstName: '' },
        contactId: null,
        customerId: 'cust-123',
      });

      req.flush({ subject: 'Witaj', bodyHtml: '<p>Witaj</p>' });
    });

    it('should send contactId in request body when provided and customerId omitted', () => {
      service.previewTemplate('tpl-1', {}, 'contact-456').subscribe();

      const req = httpMock.expectOne('/api/email-templates/tpl-1/preview');
      expect(req.request.body).toEqual({
        variables: {},
        contactId: 'contact-456',
        customerId: null,
      });

      req.flush({ subject: 'Witaj', bodyHtml: '<p>Witaj</p>' });
    });

    it('should send both contactId and customerId as null when neither is provided', () => {
      service.previewTemplate('tpl-1', {}).subscribe();

      const req = httpMock.expectOne('/api/email-templates/tpl-1/preview');
      expect(req.request.body).toEqual({
        variables: {},
        contactId: null,
        customerId: null,
      });

      req.flush({ subject: 'Witaj', bodyHtml: '<p>Witaj</p>' });
    });
  });
});
