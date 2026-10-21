import { TranslocoModule } from '@jsverse/transloco';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  viewChild,
} from '@angular/core';
import { Router } from '@angular/router';
import { IncomingCallAlertService } from '../../services/incoming-call-alert.service';
import { CallLifecycleService } from '../../services/call-lifecycle.service';
import { currentUrlSignal } from '../../../../shared/utils/router-url.util';
import { ContactTabStore } from '../../services/contact-tab.store';
import { AGENT_SHORTCUTS } from '../../models/agent-shortcuts.model';

@Component({
  selector: 'cc-incoming-call-banner',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule],
  templateUrl: './incoming-call-banner.component.html',
  styleUrl: './incoming-call-banner.component.scss',
})
export class IncomingCallBannerComponent {
  protected readonly alertService = inject(IncomingCallAlertService);
  private readonly tabStore = inject(ContactTabStore);
  private readonly calls = inject(CallLifecycleService);
  private readonly router = inject(Router);
  private readonly url = currentUrlSignal();
  private readonly bannerEl = viewChild<ElementRef<HTMLElement>>('banner');

  protected readonly pendingAlert = this.alertService.pendingAlert;
  protected readonly shortcuts = AGENT_SHORTCUTS;

  protected readonly isVisible = computed(
    () => this.pendingAlert() !== null && !this.url().startsWith('/agent/desktop'),
  );

  /** PHONE alerts are answered / rejected in place; ASYNC alerts only link to the contact. */
  protected readonly isPhone = computed(() => this.pendingAlert()?.kind === 'PHONE');

  protected readonly isInCall = computed(() => this.pendingAlert()?.kind === 'IN_CALL');

  /**
   * The banner is a manual popover so it is promoted to the top layer: it stays visible above a
   * modal <dialog> (which makes the rest of the page inert) while the keyboard shortcuts keep
   * working. Where the Popover API is missing it simply stays a fixed-position element.
   */
  private readonly promoteToTopLayer = effect(() => {
    const el = this.bannerEl()?.nativeElement;
    if (el && typeof el.showPopover === 'function' && !el.matches(':popover-open')) {
      el.showPopover();
    }
  });

  protected answer(): void {
    this.calls.answerRingingCall();
  }

  protected reject(): void {
    this.calls.rejectRingingCall();
  }

  /** Leaves the persistent "call in progress" indicator for the desktop. */
  protected openDesktop(): void {
    this.alertService.dismissAlert();
    this.router.navigate(['/agent/desktop']);
  }

  protected goToContact(): void {
    const alert = this.pendingAlert();
    const tab = alert ? this.tabStore.tabs().find((t) => t.contactId === alert.contactId) : null;
    if (tab) this.tabStore.setActiveTab(tab.id);
    this.alertService.dismissAlert();
    this.router.navigate(['/agent/desktop']);
  }

  /** Hides the banner only. A ringing call keeps ringing; the agent can still answer on the desktop. */
  protected hide(): void {
    this.alertService.dismissAlert();
  }
}
