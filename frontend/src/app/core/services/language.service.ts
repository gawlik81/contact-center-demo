import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { TranslocoService } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { catchError, of } from 'rxjs';
import { AuthService } from './auth.service';
import {
  LANGUAGE_STORAGE_KEY,
  SUPPORTED_LANGUAGES,
  SupportedLanguage,
} from '../models/language.model';
import { environment } from '../../../environments/environment';

interface UserPreferencesResponse {
  preferredLanguage: string;
}

@Injectable({ providedIn: 'root' })
export class LanguageService {
  private readonly http = inject(HttpClient);
  private readonly transloco = inject(TranslocoService);
  private readonly authService = inject(AuthService);

  private readonly _currentLang = signal<SupportedLanguage>('pl');
  readonly currentLang = this._currentLang.asReadonly();

  async init(): Promise<void> {
    const lang = await this.resolveInitialLanguage();
    this.transloco.setActiveLang(lang);
    this._currentLang.set(lang);
  }

  setLanguage(lang: SupportedLanguage): void {
    localStorage.setItem(LANGUAGE_STORAGE_KEY, lang);
    this.transloco.setActiveLang(lang);
    this._currentLang.set(lang);

    if (this.authService.isAuthenticated()) {
      this.http
        .put(`${environment.apiUrl}/users/me/preferences`, { preferredLanguage: lang })
        .pipe(
          catchError((err) => {
            console.error('[LanguageService] Failed to persist language preference:', err);
            return of(null);
          }),
        )
        .subscribe();
    }
  }

  private async resolveInitialLanguage(): Promise<SupportedLanguage> {
    if (this.authService.isAuthenticated()) {
      try {
        const prefs = await firstValueFrom(
          this.http
            .get<UserPreferencesResponse>(`${environment.apiUrl}/users/me/preferences`)
            .pipe(catchError(() => of(null))),
        );
        if (prefs?.preferredLanguage && this.isSupportedLanguage(prefs.preferredLanguage)) {
          return prefs.preferredLanguage as SupportedLanguage;
        }
      } catch {
        // fall through to next resolution step
      }
    }

    const stored = localStorage.getItem(LANGUAGE_STORAGE_KEY);
    if (stored && this.isSupportedLanguage(stored)) {
      return stored as SupportedLanguage;
    }

    const browserLang = navigator.language?.slice(0, 2);
    if (browserLang && this.isSupportedLanguage(browserLang)) {
      return browserLang as SupportedLanguage;
    }

    return 'pl';
  }

  private isSupportedLanguage(lang: string): lang is SupportedLanguage {
    return (SUPPORTED_LANGUAGES as string[]).includes(lang);
  }
}
