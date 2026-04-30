import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  ViewChild,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';
import { PhoneNumberService } from '../../../../services/phone-number.service';
import { NotificationService } from '../../../../../../core/services/notification.service';
import { ALL_DAYS, DAY_LABELS, PhoneRoutingRule } from '../../../../models/phone-number.model';
import { IvrService } from '../../../../services/ivr.service';
import { QueueService } from '../../../../services/queue.service';
import { IvrResponse } from '../../../../models/ivr.model';
import { Queue } from '../../../../models/queue.model';
import { RoutingRuleFormComponent } from './routing-rule-form/routing-rule-form.component';

@Component({
  selector: 'app-routing-rules',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslocoModule,RoutingRuleFormComponent],
  templateUrl: './routing-rules.component.html',
  styleUrl: './routing-rules.component.scss',
})
export class RoutingRulesComponent implements OnInit {
  @ViewChild('ruleFormRef') ruleFormRef!: RoutingRuleFormComponent;

  readonly phoneNumberId = input.required<string>();
  readonly phoneNumber = input<string>('');
  readonly ruleCountChange = output<number>();

  private readonly phoneNumberService = inject(PhoneNumberService);
  private readonly ivrService = inject(IvrService);
  private readonly queueService = inject(QueueService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);

  readonly loading = signal(true);
  readonly loadError = signal(false);
  readonly rules = signal<PhoneRoutingRule[]>([]);
  readonly ivrTrees = signal<IvrResponse[]>([]);
  readonly queues = signal<Queue[]>([]);
  readonly deletingRuleId = signal<string | null>(null);
  readonly editingRule = signal<PhoneRoutingRule | null>(null);
  readonly conflictingRuleIds = signal<Set<string>>(new Set());

  readonly allDays = ALL_DAYS;
  readonly dayLabels = DAY_LABELS;

  /**
   * True when there exist time slots (within any day) not covered by any rule.
   * Simplified heuristic: warn whenever there are fewer than 3 rules or
   * any rule does not span the full day (00:00–24:00).
   */
  readonly hasGaps = computed(() => {
    const r = this.rules();
    if (r.length === 0) return false;
    // Check if all 7 days and full day (00:00 - 23:59) is covered
    const fullDayCoverage = r.some(
      (rule) =>
        rule.daysOfWeek.length === 7 && rule.timeStart === '00:00' && rule.timeEnd === '23:59',
    );
    return !fullDayCoverage;
  });

  ngOnInit(): void {
    this.loadAll();
  }

  private loadAll(): void {
    this.loading.set(true);
    this.loadError.set(false);

    let rulesDone = false;
    let ivrDone = false;
    let queueDone = false;

    const checkDone = () => {
      if (rulesDone && ivrDone && queueDone) this.loading.set(false);
    };

    this.phoneNumberService
      .listRoutingRules(this.phoneNumberId())
      .pipe(
        catchError(() => {
          this.loadError.set(true);
          this.loading.set(false);
          return of([]);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((rules) => {
        this.rules.set(rules);
        rulesDone = true;
        checkDone();
      });

    this.ivrService
      .getIvrList()
      .pipe(
        catchError(() => of([])),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((trees) => {
        this.ivrTrees.set(trees);
        ivrDone = true;
        checkDone();
      });

    this.queueService
      .getQueues(0, 100)
      .pipe(
        catchError(() => of({ content: [] })),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((page) => {
        this.queues.set(page.content ?? []);
        queueDone = true;
        checkDone();
      });
  }

  openAddForm(): void {
    this.editingRule.set(null);
    // Give ViewChild time to render with null editRule, then open
    setTimeout(() => this.ruleFormRef?.open(), 0);
  }

  openEditForm(rule: PhoneRoutingRule): void {
    this.editingRule.set(rule);
    setTimeout(() => this.ruleFormRef?.open(), 0);
  }

  onRuleSaved(rule: PhoneRoutingRule): void {
    const existing = this.rules();
    const idx = existing.findIndex((r) => r.ruleId === rule.ruleId);
    if (idx >= 0) {
      this.rules.update((list) => list.map((r) => (r.ruleId === rule.ruleId ? rule : r)));
    } else {
      this.rules.update((list) => [...list, rule]);
      this.ruleCountChange.emit(this.rules().length);
    }
    this.editingRule.set(null);
    this.conflictingRuleIds.set(new Set());
  }

  onFormCancelled(): void {
    this.editingRule.set(null);
  }

  deleteRule(rule: PhoneRoutingRule): void {
    if (this.deletingRuleId()) return;
    this.deletingRuleId.set(rule.ruleId);

    this.phoneNumberService
      .deleteRoutingRule(this.phoneNumberId(), rule.ruleId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.rules.update((list) => list.filter((r) => r.ruleId !== rule.ruleId));
          this.ruleCountChange.emit(this.rules().length);
          this.notifications.success(this.transloco.translate('supervisor.settings.routingRules.successDelete'));
          this.deletingRuleId.set(null);
        },
        error: (err: unknown) => {
          const status = (err as { status?: number })?.status;
          if (status === 409) {
            this.notifications.error(this.transloco.translate('supervisor.settings.routingRules.errorDelete'));
          } else {
            this.notifications.error(this.transloco.translate('supervisor.settings.routingRules.errorDelete'));
          }
          this.deletingRuleId.set(null);
        },
      });
  }

  getIvrName(ivrTreeId: string): string {
    return this.ivrTrees().find((t) => t.ivr_id === ivrTreeId)?.name ?? ivrTreeId;
  }

  getQueueName(queueId: string): string {
    return this.queues().find((q) => q.queueId === queueId)?.name ?? queueId;
  }

  isConflicting(ruleId: string): boolean {
    return this.conflictingRuleIds().has(ruleId);
  }

  formatDays(days: number[]): string {
    return [...days]
      .sort((a, b) => a - b)
      .map((d) => DAY_LABELS[d])
      .join(', ');
  }

  protected readonly trackByRuleId = (_: number, r: PhoneRoutingRule) => r.ruleId;
}
