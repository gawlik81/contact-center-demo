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

  private readonly twilioDeviceEffect = effect(() => {
    const status = this.statusService.currentStatus();
    if (status === 'AVAILABLE') {
      const alreadyReady = untracked(() => this.softphoneService.twilioDeviceReady());
      if (!alreadyReady) {
        this.softphoneService.initializeTwilioDevice().catch((err) => {
          console.error('[AgentShell] initializeTwilioDevice failed:', err);
        });
      }
    }
  });

  ngOnInit(): void {
    this.ws.connect();
    const unregisterRecovery = this.ws.onConnect(() => {
      this.recoveryService.recoverAfterReconnect();
    });
    this.destroyRef.onDestroy(() => unregisterRecovery());
  }

  ngOnDestroy(): void {
    this.ws.disconnect();
  }
}
