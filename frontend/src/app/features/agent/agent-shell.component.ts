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
import { ContactDraftStore } from './services/contact-draft.store';
import { ContactTabStore } from './services/contact-tab.store';
import { AgentRecoveryService } from './services/agent-recovery.service';
import { AgentHotkeysService } from './services/agent-hotkeys.service';
import { CallLifecycleService } from './services/call-lifecycle.service';
import { AcwBannerComponent } from './components/acw-banner/acw-banner.component';
import { AgentShortcutsCheatSheetComponent } from './components/agent-shortcuts-cheat-sheet/agent-shortcuts-cheat-sheet.component';

@Component({
  selector: 'app-agent-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AppShellComponent,
    IncomingCallBannerComponent,
    AcwBannerComponent,
    AgentShortcutsCheatSheetComponent,
  ],
  // Component-level provider: the global keydown listener lives only while the agent shell does.
  providers: [AgentHotkeysService],
  template: `
    <!-- Early in the DOM so a keyboard user reaches it without tabbing through the whole page;
         it is position: fixed, so its visual place does not depend on this order. -->
    <cc-acw-banner />
    <cc-app-shell />
    <cc-incoming-call-banner />
    <cc-agent-shortcuts-cheat-sheet />
  `,
})
export class AgentShellComponent implements OnInit, OnDestroy {
  private readonly ws = inject(WebSocketService);
  private readonly statusService = inject(AgentStatusService);
  private readonly softphoneService = inject(SoftphoneService);
  private readonly draftStore = inject(ContactDraftStore);
  private readonly tabStore = inject(ContactTabStore);
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

  constructor() {
    // Instantiate the component-provided service so the shortcuts are active on every agent route.
    inject(AgentHotkeysService);
    // Root service owning call side effects (BUSY / ACW / WS call events) on every agent route.
    inject(CallLifecycleService);
  }

  ngOnInit(): void {
    // Status is owned by the shell (not the desktop page) so it is loaded and kept in sync via
    // WebSocket on every /agent/* route; the top-bar switcher and the desktop share this signal.
    this.statusService.initStatus();
    this.destroyRef.onDestroy(() => this.statusService.stopStatus());
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
    // Drafts hold customer message content in memory; do not let them outlive the session.
    this.draftStore.clearAll();
    // Tabs are a root singleton too: without this the next agent logging in on the same browser
    // tab would see the previous agent's contacts (names, contactIds).
    this.tabStore.reset();
  }
}
