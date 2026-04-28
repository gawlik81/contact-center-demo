import {
  APP_INITIALIZER,
  ApplicationConfig,
  ErrorHandler,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter, withComponentInputBinding, withRouterConfig } from '@angular/router';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { provideTransloco } from '@jsverse/transloco';

import { routes } from './app.routes';
import { authInterceptor } from './core/interceptors/auth.interceptor';
import { errorHandlerInterceptor } from './core/interceptors/error-handler.interceptor';
import { AppErrorHandler } from './core/app-error-handler';
import { TranslocoHttpLoader } from './core/transloco-http-loader';
import { LanguageService } from './core/services/language.service';
import { environment } from '../environments/environment';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(
      routes,
      withComponentInputBinding(),
      withRouterConfig({ paramsInheritanceStrategy: 'always' }),
    ),
    provideHttpClient(withFetch(), withInterceptors([authInterceptor, errorHandlerInterceptor])),
    { provide: ErrorHandler, useClass: AppErrorHandler },
    provideTransloco({
      config: {
        availableLangs: ['pl', 'en', 'de'],
        defaultLang: 'pl',
        fallbackLang: 'en',
        reRenderOnLangChange: true,
        prodMode: environment.production,
      },
      loader: TranslocoHttpLoader,
    }),
    {
      provide: APP_INITIALIZER,
      useFactory: (languageService: LanguageService) => () => languageService.init(),
      deps: [LanguageService],
      multi: true,
    },
  ],
};
