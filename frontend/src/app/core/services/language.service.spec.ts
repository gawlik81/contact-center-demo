import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TranslocoService, TranslocoTestingModule } from '@jsverse/transloco';
import { signal } from '@angular/core';
import { vi } from 'vitest';

import { LanguageService } from './language.service';
import { AuthService } from './auth.service';
import { LANGUAGE_STORAGE_KEY } from '../models/language.model';

// ── Helpers ───────────────────────────────────────────────────────────────────

const PREFS_URL = '/api/users/me/preferences';

function buildAuthServiceMock(authenticated: boolean): Partial<AuthService> {
  return {
    isAuthenticated: signal(authenticated),
  };
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('LanguageService', () => {
  let service: LanguageService;
  let httpMock: HttpTestingController;
  let translocoService: TranslocoService;

  function setup(authenticated: boolean) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [
        TranslocoTestingModule.forRoot({
          langs: { pl: {}, en: {}, de: {} },
          translocoConfig: {
            availableLangs: ['pl', 'en', 'de'],
            defaultLang: 'pl',
          },
        }),
      ],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        LanguageService,
        { provide: AuthService, useValue: buildAuthServiceMock(authenticated) },
      ],
    });

    service = TestBed.inject(LanguageService);
    httpMock = TestBed.inject(HttpTestingController);
    translocoService = TestBed.inject(TranslocoService);
  }

  beforeEach(() => {
    localStorage.removeItem(LANGUAGE_STORAGE_KEY);
    // Reset navigator.language to a non-supported value so tests don't bleed into each other
    Object.defineProperty(navigator, 'language', { value: 'xx-XX', configurable: true });
  });

  afterEach(() => {
    localStorage.removeItem(LANGUAGE_STORAGE_KEY);
    httpMock.verify();
    TestBed.resetTestingModule();
  });

  // ── init() ──────────────────────────────────────────────────────────────────

  describe('init()', () => {
    it('uses language from backend when user is authenticated', async () => {
      setup(true);
      const setSpy = vi.spyOn(translocoService, 'setActiveLang');

      const initPromise = service.init();

      const req = httpMock.expectOne(PREFS_URL);
      expect(req.request.method).toBe('GET');
      req.flush({ preferredLanguage: 'en' });

      await initPromise;

      expect(setSpy).toHaveBeenCalledWith('en');
      expect(service.currentLang()).toBe('en');
    });

    it('uses localStorage when user is not authenticated', async () => {
      localStorage.setItem(LANGUAGE_STORAGE_KEY, 'de');
      setup(false);
      const setSpy = vi.spyOn(translocoService, 'setActiveLang');

      await service.init();

      httpMock.expectNone(PREFS_URL);
      expect(setSpy).toHaveBeenCalledWith('de');
      expect(service.currentLang()).toBe('de');
    });

    it('uses navigator.language when no localStorage and not authenticated', async () => {
      Object.defineProperty(navigator, 'language', { value: 'de-DE', configurable: true });

      setup(false);
      const setSpy = vi.spyOn(translocoService, 'setActiveLang');

      await service.init();

      httpMock.expectNone(PREFS_URL);
      expect(setSpy).toHaveBeenCalledWith('de');
      expect(service.currentLang()).toBe('de');
    });

    it('falls back to "pl" when nothing is set', async () => {
      // beforeEach already sets navigator.language to 'xx-XX' (unsupported) and clears localStorage
      setup(false);
      const setSpy = vi.spyOn(translocoService, 'setActiveLang');

      await service.init();

      expect(setSpy).toHaveBeenCalledWith('pl');
      expect(service.currentLang()).toBe('pl');
    });

    it('falls back to "pl" when backend returns unsupported language', async () => {
      setup(true);
      const setSpy = vi.spyOn(translocoService, 'setActiveLang');

      const initPromise = service.init();

      const req = httpMock.expectOne(PREFS_URL);
      req.flush({ preferredLanguage: 'fr' });

      await initPromise;

      expect(setSpy).toHaveBeenCalledWith('pl');
      expect(service.currentLang()).toBe('pl');
    });

    it('falls back to localStorage when backend request fails', async () => {
      localStorage.setItem(LANGUAGE_STORAGE_KEY, 'en');
      setup(true);
      const setSpy = vi.spyOn(translocoService, 'setActiveLang');

      const initPromise = service.init();

      const req = httpMock.expectOne(PREFS_URL);
      req.flush('Server error', { status: 500, statusText: 'Internal Server Error' });

      await initPromise;

      expect(setSpy).toHaveBeenCalledWith('en');
      expect(service.currentLang()).toBe('en');
    });
  });

  // ── setLanguage() ───────────────────────────────────────────────────────────

  describe('setLanguage()', () => {
    it('updates signal, localStorage and Transloco, and calls PUT when authenticated', () => {
      setup(true);
      const setSpy = vi.spyOn(translocoService, 'setActiveLang');

      service.setLanguage('en');

      expect(service.currentLang()).toBe('en');
      expect(localStorage.getItem(LANGUAGE_STORAGE_KEY)).toBe('en');
      expect(setSpy).toHaveBeenCalledWith('en');

      const req = httpMock.expectOne(PREFS_URL);
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual({ preferredLanguage: 'en' });
      req.flush(null);
    });

    it('does not call PUT when user is not authenticated', () => {
      setup(false);

      service.setLanguage('de');

      httpMock.expectNone(PREFS_URL);
      expect(service.currentLang()).toBe('de');
    });

    it('backend error does not revert UI language change', () => {
      setup(true);
      const setSpy = vi.spyOn(translocoService, 'setActiveLang');

      service.setLanguage('de');

      const req = httpMock.expectOne(PREFS_URL);
      req.flush('Error', { status: 500, statusText: 'Server Error' });

      // Language must remain 'de' despite backend error
      expect(service.currentLang()).toBe('de');
      expect(setSpy).toHaveBeenCalledWith('de');
      expect(localStorage.getItem(LANGUAGE_STORAGE_KEY)).toBe('de');
    });
  });
});
