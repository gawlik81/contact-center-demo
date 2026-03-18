import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';

// @stomp/stompjs requires global to be defined in browser/Vite environments
(window as any).global = window;

bootstrapApplication(App, appConfig).catch((err) => console.error(err));
