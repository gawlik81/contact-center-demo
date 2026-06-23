import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { NgClass } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  ViewChild,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NotificationService } from '../../../../../core/services/notification.service';
import { ConfirmDialogComponent } from '../../../../../shared/components/confirm-dialog/confirm-dialog.component';
import { PluginAdminService } from '../../../../plugins/services/plugin-admin.service';
import {
  PluginHealthStatus,
  PluginVersionDto,
  TenantPluginInstallationDto,
} from '../../../../plugins/models/plugin.model';

const MAX_JAR_SIZE_BYTES = 50 * 1024 * 1024;

@Component({
  selector: 'app-plugins-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, NgClass, ConfirmDialogComponent],
  templateUrl: './plugins-page.component.html',
  styleUrl: './plugins-page.component.scss',
})
export class PluginsPageComponent implements OnInit {
  @ViewChild('installDialog') private installDialogRef!: ElementRef<HTMLDialogElement>;
  @ViewChild('fileInput') private fileInputRef!: ElementRef<HTMLInputElement>;

  private readonly pluginAdminService = inject(PluginAdminService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);

  // ---- Upload state ----

  readonly uploading = signal(false);
  readonly isDragOver = signal(false);
  readonly uploadResult = signal<PluginVersionDto | null>(null);
  readonly uploadClientError = signal<string | null>(null);

  readonly uploadIsRejected = computed(() => this.uploadResult()?.status === 'REJECTED');
  readonly uploadIsInstallable = computed(() => {
    const status = this.uploadResult()?.status;
    return status === 'VALIDATED' || status === 'PENDING_REVIEW';
  });

  // ---- Installations list state ----

  readonly loading = signal(true);
  readonly loadError = signal(false);
  readonly installations = signal<TenantPluginInstallationDto[]>([]);

  // ---- Install dialog state ----

  readonly grantedPermissions = signal<Set<string>>(new Set());
  readonly installing = signal(false);

  // ---- Uninstall confirm dialog state ----

  readonly targetUninstall = signal<TenantPluginInstallationDto | null>(null);
  readonly uninstalling = signal(false);

  readonly uninstallMessage = computed(() => {
    const installation = this.targetUninstall();
    if (!installation) return '';
    return this.transloco.translate('supervisor.settings.plugins.confirmUninstall', {
      name: installation.displayName,
    });
  });

  /** Per-installation pending action flag (enable/disable/rollback), keyed by installation id. */
  readonly pendingActionIds = signal<Set<string>>(new Set());

  ngOnInit(): void {
    this.loadInstallations();
  }

  // ---- Upload ----

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.isDragOver.set(true);
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    this.isDragOver.set(false);
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.isDragOver.set(false);
    const file = event.dataTransfer?.files?.[0];
    if (file) this.handleFile(file);
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (file) this.handleFile(file);
    input.value = '';
  }

  openFilePicker(): void {
    this.fileInputRef.nativeElement.click();
  }

  private handleFile(file: File): void {
    this.uploadClientError.set(null);
    this.uploadResult.set(null);

    if (!file.name.toLowerCase().endsWith('.jar')) {
      this.uploadClientError.set(
        this.transloco.translate('supervisor.settings.plugins.errorInvalidExtension'),
      );
      return;
    }
    if (file.size > MAX_JAR_SIZE_BYTES) {
      this.uploadClientError.set(
        this.transloco.translate('supervisor.settings.plugins.errorTooLarge'),
      );
      return;
    }

    this.uploading.set(true);
    this.pluginAdminService
      .uploadJar(file)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.uploading.set(false);
          this.uploadResult.set(result);
          if (result.status === 'REJECTED') {
            this.notifications.error(
              this.transloco.translate('supervisor.settings.plugins.errorRejected'),
            );
          } else {
            this.notifications.success(
              this.transloco.translate('supervisor.settings.plugins.successUpload'),
            );
          }
        },
        error: () => {
          this.uploading.set(false);
          this.notifications.error(
            this.transloco.translate('supervisor.settings.plugins.errorUpload'),
          );
        },
      });
  }

  clearUploadResult(): void {
    this.uploadResult.set(null);
    this.uploadClientError.set(null);
  }

  // ---- Installations list ----

  loadInstallations(): void {
    this.loading.set(true);
    this.loadError.set(false);
    this.pluginAdminService
      .listInstallations()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (list) => {
          this.installations.set(list);
          this.loading.set(false);
        },
        error: () => {
          this.loadError.set(true);
          this.loading.set(false);
        },
      });
  }

  healthBadgeClass(status: PluginHealthStatus): string {
    switch (status) {
      case 'HEALTHY':
        return 'pp-badge--healthy';
      case 'DEGRADED':
        return 'pp-badge--degraded';
      case 'DISABLED_BY_ADMIN':
        return 'pp-badge--disabled';
    }
  }

  /**
   * Znajduje starszą, wyłączoną instalację tego samego `pluginKey` — kandydata na `targetId`
   * do rollbacku. Zwraca `null` gdy nie istnieje (np. tylko jedna wersja zainstalowana).
   */
  rollbackTargetFor(installation: TenantPluginInstallationDto): TenantPluginInstallationDto | null {
    const candidates = this.installations().filter(
      (i) =>
        i.pluginKey === installation.pluginKey && i.id !== installation.id && i.enabled === false,
    );
    if (candidates.length === 0) return null;
    return candidates.reduce((oldest, current) =>
      new Date(current.installedAt) < new Date(oldest.installedAt) ? current : oldest,
    );
  }

  isPending(installationId: string): boolean {
    return this.pendingActionIds().has(installationId);
  }

  private setPending(installationId: string, pending: boolean): void {
    this.pendingActionIds.update((set) => {
      const next = new Set(set);
      if (pending) next.add(installationId);
      else next.delete(installationId);
      return next;
    });
  }

  toggleEnabled(installation: TenantPluginInstallationDto): void {
    if (this.isPending(installation.id)) return;
    this.setPending(installation.id, true);
    const action$ = installation.enabled
      ? this.pluginAdminService.disable(installation.id)
      : this.pluginAdminService.enable(installation.id);

    action$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.setPending(installation.id, false);
        this.installations.update((list) =>
          list.map((i) => (i.id === installation.id ? { ...i, enabled: !i.enabled } : i)),
        );
        this.notifications.success(
          this.transloco.translate(
            installation.enabled
              ? 'supervisor.settings.plugins.successDisabled'
              : 'supervisor.settings.plugins.successEnabled',
          ),
        );
      },
      error: () => {
        this.setPending(installation.id, false);
        this.notifications.error(
          this.transloco.translate('supervisor.settings.plugins.errorToggle'),
        );
      },
    });
  }

  rollback(installation: TenantPluginInstallationDto): void {
    const target = this.rollbackTargetFor(installation);
    if (!target || this.isPending(installation.id)) return;

    this.setPending(installation.id, true);
    this.pluginAdminService
      .rollback(installation.id, target.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.setPending(installation.id, false);
          this.notifications.success(
            this.transloco.translate('supervisor.settings.plugins.successRollback'),
          );
          this.loadInstallations();
        },
        error: () => {
          this.setPending(installation.id, false);
          this.notifications.error(
            this.transloco.translate('supervisor.settings.plugins.errorRollback'),
          );
        },
      });
  }

  // ---- Install dialog ----

  openInstallDialog(): void {
    this.grantedPermissions.set(new Set());
    this.installDialogRef.nativeElement.showModal();
  }

  closeInstallDialog(): void {
    this.installDialogRef.nativeElement.close();
  }

  isPermissionGranted(permission: string): boolean {
    return this.grantedPermissions().has(permission);
  }

  togglePermission(permission: string): void {
    this.grantedPermissions.update((set) => {
      const next = new Set(set);
      if (next.has(permission)) next.delete(permission);
      else next.add(permission);
      return next;
    });
  }

  confirmInstall(): void {
    const version = this.uploadResult();
    if (!version || this.installing()) return;

    this.installing.set(true);
    this.pluginAdminService
      .install({
        pluginVersionId: version.id,
        grantedPermissions: Array.from(this.grantedPermissions()),
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.installing.set(false);
          this.closeInstallDialog();
          this.clearUploadResult();
          this.notifications.success(
            this.transloco.translate('supervisor.settings.plugins.successInstall'),
          );
          this.loadInstallations();
        },
        error: () => {
          this.installing.set(false);
          this.notifications.error(
            this.transloco.translate('supervisor.settings.plugins.errorInstall'),
          );
        },
      });
  }

  // ---- Uninstall confirm dialog ----

  openUninstallDialog(installation: TenantPluginInstallationDto): void {
    this.targetUninstall.set(installation);
  }

  cancelUninstall(): void {
    this.targetUninstall.set(null);
  }

  confirmUninstall(): void {
    const installation = this.targetUninstall();
    if (!installation) return;

    this.uninstalling.set(true);
    this.pluginAdminService
      .uninstall(installation.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.uninstalling.set(false);
          this.targetUninstall.set(null);
          this.installations.update((list) => list.filter((i) => i.id !== installation.id));
          this.notifications.success(
            this.transloco.translate('supervisor.settings.plugins.successUninstall'),
          );
        },
        error: () => {
          this.uninstalling.set(false);
          this.notifications.error(
            this.transloco.translate('supervisor.settings.plugins.errorUninstall'),
          );
        },
      });
  }

  protected readonly trackByInstallationId = (_index: number, item: TenantPluginInstallationDto) =>
    item.id;
}
