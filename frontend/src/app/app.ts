import { Component, inject, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ToastContainerComponent } from './shared/components/toast/toast-container.component';
import { IncomingCallAlertService } from './features/agent/services/incoming-call-alert.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, ToastContainerComponent],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  protected readonly title = signal('contact-center-frontend');

  // Eagerly initialize the IncomingCallAlertService so it starts listening for
  // CALL_INCOMING / CONTACT_ASSIGNED WebSocket events as soon as the app boots,
  // regardless of which route is currently active.
  private readonly _incomingCallAlert = inject(IncomingCallAlertService);
}
