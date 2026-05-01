import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { CustomerService } from './customer.service';
import { CustomerResponse, PagedResponse } from '../../../models/customer.model';

const mockCustomer: CustomerResponse = {
  customerId: 'c1',
  tenantId: 't1',
  firstName: 'Jan',
  lastName: 'Kowalski',
  phone: ['48100200300'],
  email: ['jan@example.com'],
  customFields: {},
  gdprConsent: { consent_given: true },
  source: 'MANUAL',
  isDeleted: false,
  createdAt: '2025-01-15T10:00:00Z',
};

const mockPage: PagedResponse<CustomerResponse> = {
  content: [mockCustomer],
  totalElements: 1,
  totalPages: 1,
  page: 0,
  size: 20,
};

describe('CustomerService', () => {
  let service: CustomerService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
    });
    service = TestBed.inject(CustomerService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('getCustomers – sends request without q when query is empty', () => {
    service.getCustomers({ page: 0, size: 20 }).subscribe((res) => {
      expect(res.content.length).toBe(1);
    });

    const req = httpMock.expectOne((r) => r.url.includes('/api/customers'));
    expect(req.request.params.has('q')).toBe(false);
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('20');
    req.flush(mockPage);
  });

  it('getCustomers – sends q param when query has at least 2 chars', () => {
    service.getCustomers({ q: 'Ko', page: 0, size: 20 }).subscribe();

    const req = httpMock.expectOne((r) => r.url.includes('/api/customers'));
    expect(req.request.params.get('q')).toBe('Ko');
    req.flush(mockPage);
  });

  it('getCustomers – does NOT send q when query is only 1 char', () => {
    service.getCustomers({ q: 'K', page: 0, size: 20 }).subscribe();

    const req = httpMock.expectOne((r) => r.url.includes('/api/customers'));
    expect(req.request.params.has('q')).toBe(false);
    req.flush(mockPage);
  });

  it('getCustomers – sends sort param when provided', () => {
    service.getCustomers({ page: 0, size: 20, sort: 'lastName,asc' }).subscribe();

    const req = httpMock.expectOne((r) => r.url.includes('/api/customers'));
    expect(req.request.params.get('sort')).toBe('lastName,asc');
    req.flush(mockPage);
  });

  it('getCustomer – GET /api/customers/:id', () => {
    service.getCustomer('c1').subscribe((c) => {
      expect(c.customerId).toBe('c1');
    });

    const req = httpMock.expectOne('/api/customers/c1');
    expect(req.request.method).toBe('GET');
    req.flush(mockCustomer);
  });

  it('deleteCustomer – DELETE /api/customers/:id returns 204', () => {
    service.deleteCustomer('c1').subscribe();

    const req = httpMock.expectOne('/api/customers/c1');
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });
  });
});
