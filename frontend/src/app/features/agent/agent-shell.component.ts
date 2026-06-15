import {
  Component,
  ChangeDetectionStrategy,
  OnInit,
  OnDestroy,
  DestroyRef,
  effect,
  inject,
  untracked,
} from '@angular/core';
import { AppShellComponent } from '../../shared/components/app-shell/app-shell.component';
import { IncomingCallBannerComponent } from './components/incoming-call-banner/incoming-call-banner.component';
import { WebSocketService } from '../../core/services/websocket.service';
import { AgentStatusService } from './services/agent-status.service';
import { SoftphoneService } from './services/softphone.service';
import { AgentRecoveryService } from './services/agent-recovery.service';

@Component({
  selector: 'app-agent-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AppShellComponent, IncomingCallBannerComponent],
  template: `
    <cc-app-shell />
    <cc-incoming-call-banner />
  `,
})
export class AgentShellComponent implements OnInit, OnDestroy {
  private readonly ws = inject(WebSocketService);
  private readonly statusService = inject(AgentStatusService);
  private readonly softphoneService = inject(SoftphoneService);
  private readonly recoveryService = inject(AgentRecoveryService);
  private readonly destroyRef = inject(DestroyRef);

  /**
   * Ensures the Twilio Voice Device is (re)initialized whenever the agent becomes
   * AVAILABLE and no device is currently registered.
   *
   * Both `currentStatus()` and `twilioDeviceReady()` are tracked dependencies —
   * this is required so the effect re-runs after `resetForLogout()` even if
   * `currentStatus` settles back to the same value it had during the previous
   * agent's session (e.g. 'AVAILABLE' -> 'OFFLINE' -> 'AVAILABLE', where the
   * net change from the effect's perspective could otherwise be "no change").
   * Without tracking `twilioDeviceReady`, the `resetForLogout()` call (which sets
   * it back to false for the new agent) would not by itself trigger a re-run,
   * and `initializeTwilioDevice()` would silently never be called for the next
   * agent logging into the same tab.
   */
  private readonly twilioDeviceEffect = effect(() => {
    const status = this.statusService.currentStatus();
    const ready = this.softphoneService.twilioDeviceReady();
    const initInProgress = untracked(() => this.twilioDeviceInitInProgress);
    if (status === 'AVAILABLE' && !ready && !initInProgress) {
      this.twilioDeviceInitInProgress = true;
      this.softphoneService
        .initializeTwilioDevice()
        .catch((err) => {
          console.error('[AgentShell] initializeTwilioDevice failed:', err);
        })
        .finally(() => {
          this.twilioDeviceInitInProgress = false;
        });
    }
  });

  /**
   * Guards against re-entrant `initializeTwilioDevice()` calls while the previous
   * call is still pending (e.g. the effect re-runs because `currentStatus` flips
   * 'OFFLINE' -> 'AVAILABLE' twice in quick succession during `initStatus()`).
   */
  private twilioDeviceInitInProgress = false;

  ngOnInit(): void {
    this.ws.connect();
    const unregisterRecovery = this.ws.onConnect(() => {
      this.recoveryService.recoverAfterReconnect();
    });
    this.destroyRef.onDestroy(() => unregisterRecovery());
  }

  ngOnDestroy(): void {
    this.ws.disconnect();
    // SoftphoneService is providedIn: 'root' and survives logout in the same
    // tab — reset its Twilio Device so the next agent who logs in starts
    // with a fresh Device instead of inheriting a stale/broken one.
    this.softphoneService.resetForLogout();
  }
}
