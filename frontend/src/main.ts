import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';
import { VERSION } from './environments/version';

// @stomp/stompjs requires global to be defined in browser/Vite environments
// eslint-disable-next-line @typescript-eslint/no-explicit-any
(window as any).global = window;

console.info(`Contact Center Frontend %s (branch: %s)`, VERSION.version, VERSION.branch);

bootstrapApplication(App, appConfig).catch((err) => console.error(err));
