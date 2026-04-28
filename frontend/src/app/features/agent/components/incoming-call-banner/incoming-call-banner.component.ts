import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { Router } from '@angular/router';
import { IncomingCallAlertService } from '../../services/incoming-call-alert.service';

@Component({
  selector: 'cc-incoming-call-banner',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './incoming-call-banner.component.html',
  styleUrl: './incoming-call-banner.component.scss',
})
export class IncomingCallBannerComponent {
  protected readonly alertService = inject(IncomingCallAlertService);
  private readonly router = inject(Router);

  protected readonly pendingAlert = this.alertService.pendingAlert;

  protected readonly isVisible = computed(
    () => this.pendingAlert() !== null && !this.router.url.startsWith('/agent/desktop'),
  );

  protected goToDesktop(): void {
    this.alertService.dismissAlert();
    this.router.navigate(['/agent/desktop']);
  }

  protected dismiss(): void {
    this.alertService.dismissAlert();
  }
}
