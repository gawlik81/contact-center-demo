import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  OnInit,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { SlicePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { catchError, of } from 'rxjs';
import { IvrService } from '../../../services/ivr.service';
import { QueueService } from '../../../services/queue.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import {
  IVR_NODE_LABELS,
  IvrDefinitionUI,
  IvrNodeType,
  IvrNodeUI,
  IvrOption,
  IvrResponse,
} from '../../../models/ivr.model';

interface SvgConnection {
  fromNodeId: string;
  fromOptionKey: string;
  toNodeId: string;
  pathD: string;
}

interface DragState {
  nodeId: string;
  startMouseX: number;
  startMouseY: number;
  startNodeX: number;
  startNodeY: number;
}

const NODE_WIDTH = 200;
const NODE_HEIGHT_BASE = 80;

@Component({
  selector: 'app-ivr-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, FormsModule, SlicePipe],
  templateUrl: './ivr-editor.component.html',
  styleUrl: './ivr-editor.component.scss',
})
export class IvrEditorComponent implements OnInit {
  private readonly ivrService = inject(IvrService);
  private readonly queueService = inject(QueueService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly canvasRef = viewChild<ElementRef<HTMLDivElement>>('canvasRef');

  // ─── State ──────────────────────────────────────────────────────────────────

  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);

  readonly ivrId = signal<string>('');
  readonly ivrName = signal('');
  readonly ivrVersion = signal(0);
  readonly ivrIsActive = signal(false);

  readonly definition = signal<IvrDefinitionUI>({ nodes: [], entry_node_id: '' });
  readonly selectedNodeId = signal<string | null>(null);
  readonly showDebugPanel = signal(false);
  readonly showWarningsModal = signal(false);
  readonly pendingWarnings = signal<string[]>([]);

  // Connection drawing state
  readonly connectingFrom = signal<{ nodeId: string; optionKey: string } | null>(null);

  // Drag state (not a signal – updated at high frequency)
  private dragState: DragState | null = null;

  // Available queues for QUEUE_TRANSFER dropdown
  readonly queues = signal<{ id: string; name: string }[]>([]);

  // Audio upload state
  readonly uploadInProgress = signal<string | null>(null); // nodeId being uploaded
  readonly uploadProgress = signal(0);

  // ─── Computed ───────────────────────────────────────────────────────────────

  readonly selectedNode = computed<IvrNodeUI | null>(() => {
    const id = this.selectedNodeId();
    if (!id) return null;
    return this.definition().nodes.find((n) => n.node_id === id) ?? null;
  });

  readonly svgConnections = computed<SvgConnection[]>(() => {
    const def = this.definition();
    const connections: SvgConnection[] = [];
    for (const node of def.nodes) {
      for (const opt of node.options ?? []) {
        if (!opt.next_node_id) continue;
        const toNode = def.nodes.find((n) => n.node_id === opt.next_node_id);
        if (!toNode) continue;
        connections.push({
          fromNodeId: node.node_id,
          fromOptionKey: opt.key,
          toNodeId: opt.next_node_id,
          pathD: this.computeBezierPath(node, opt.key, toNode),
        });
      }
    }
    return connections;
  });

  readonly apiDefinitionJson = computed(() => {
    const api = this.ivrService.toApiDefinition(this.definition());
    return JSON.stringify(api, null, 2);
  });

  readonly nodeLabels = IVR_NODE_LABELS;

  getNodeLabel(type: IvrNodeType): string {
    return this.transloco.translate(IVR_NODE_LABELS[type]);
  }

  readonly nodeTypes: IvrNodeType[] = [
    'PLAY_AUDIO',
    'MENU',
    'COLLECT_DTMF',
    'QUEUE_TRANSFER',
    'HANGUP',
    'SET',
    'IF',
    'SWITCH',
    'VOICEBOT',
  ];

  readonly nodeTypeIcons: Record<IvrNodeType, string> = {
    PLAY_AUDIO: 'A',
    MENU: 'M',
    COLLECT_DTMF: 'D',
    QUEUE_TRANSFER: 'Q',
    HANGUP: 'H',
    SET: 'S',
    IF: '?',
    SWITCH: '⊞',
    VOICEBOT: 'VB',
  };

  // ─── Lifecycle ──────────────────────────────────────────────────────────────

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('ivrId') ?? '';
    this.ivrId.set(id);
    this.loadIvr(id);
    this.loadQueues();
  }

  // ─── Load ────────────────────────────────────────────────────────────────────

  private loadIvr(id: string): void {
    this.loading.set(true);
    this.ivrService
      .getIvr(id)
      .pipe(
        catchError((_err) => {
          this.error.set(this.transloco.translate('supervisor.ivrEditor.errorLoad'));
          this.loading.set(false);
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((ivr) => {
        if (!ivr) return;
        this.applyIvrResponse(ivr);
        this.loading.set(false);
      });
  }

  private loadQueues(): void {
    this.queueService
      .getQueues(0, 100)
      .pipe(
        catchError(() => of(null)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((page) => {
        if (!page) return;
        // Backend sends queueId (camelCase, no @JsonProperty), frontend model has id
        this.queues.set(
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          page.content.map((q: any) => ({ id: q.queueId ?? q.id, name: q.name })),
        );
      });
  }

  private applyIvrResponse(ivr: IvrResponse): void {
    this.ivrName.set(ivr.name);
    this.ivrVersion.set(ivr.version);
    this.ivrIsActive.set(ivr.is_active);
    const uiDef = this.ivrService.toUIDefinition(ivr.ivr_id, ivr);
    this.definition.set(uiDef);
  }

  // ─── Save ────────────────────────────────────────────────────────────────────

  onSave(): void {
    const warnings = this.validateDefinition(this.definition());
    if (warnings.length > 0) {
      this.pendingWarnings.set(warnings);
      this.showWarningsModal.set(true);
      return;
    }
    this.doSave();
  }

  onSaveAnyway(): void {
    this.showWarningsModal.set(false);
    this.doSave();
  }

  private doSave(): void {
    this.saving.set(true);
    const apiDef = this.ivrService.toApiDefinition(this.definition());
    this.ivrService
      .updateIvr(this.ivrId(), {
        name: this.ivrName(),
        definition: apiDef as { nodes: never[]; entry_node_id: string },
      })
      .pipe(
        catchError(() => {
          this.notifications.error(this.transloco.translate('supervisor.ivrEditor.errorSave'));
          this.saving.set(false);
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((ivr) => {
        this.saving.set(false);
        if (ivr) {
          this.ivrService.savePositions(this.ivrId(), this.definition().nodes);
          this.ivrVersion.set(ivr.version);
          this.notifications.success(this.transloco.translate('supervisor.ivrEditor.successSave'));
        }
      });
  }

  // ─── Validation ─────────────────────────────────────────────────────────────

  validateDefinition(def: IvrDefinitionUI): string[] {
    const warnings: string[] = [];
    const nodeIds = new Set(def.nodes.map((n) => n.node_id));

    if (!def.entry_node_id) {
      warnings.push(this.transloco.translate('supervisor.ivr.warnNoEntryNode'));
    } else if (!nodeIds.has(def.entry_node_id)) {
      warnings.push(this.transloco.translate('supervisor.ivr.warnEntryNodeMissing'));
    }

    for (const node of def.nodes) {
      if (
        (node.type === 'MENU' || node.type === 'COLLECT_DTMF') &&
        (!node.options || node.options.length === 0)
      ) {
        warnings.push(
          this.transloco.translate('supervisor.ivr.warnNoOptions', {
            label: node.prompt?.slice(0, 30) ?? node.node_id,
            type: node.type,
          }),
        );
      }
      if (node.type === 'COLLECT_DTMF' && !node.variable_name?.trim()) {
        warnings.push(
          this.transloco.translate('supervisor.ivr.warnDtmfNoVariable', {
            label: node.prompt?.slice(0, 30) ?? node.node_id,
          }),
        );
      }
      if (node.type === 'SET' && !node.variable_name?.trim()) {
        warnings.push(
          this.transloco.translate('supervisor.ivr.warnSetNoVariable', { id: node.node_id }),
        );
      }
      if (node.type === 'IF' && !node.variable_name?.trim()) {
        warnings.push(
          this.transloco.translate('supervisor.ivr.warnIfNoVariable', { id: node.node_id }),
        );
      }
      if (node.type === 'SWITCH') {
        if (!node.variable_name?.trim()) {
          warnings.push(
            this.transloco.translate('supervisor.ivr.warnSwitchNoVariable', { id: node.node_id }),
          );
        }
        const hasDefault = (node.options ?? []).some((o) => o.key === 'default');
        if (!hasDefault) {
          warnings.push(
            this.transloco.translate('supervisor.ivr.warnSwitchNoDefault', { id: node.node_id }),
          );
        }
      }
      if (node.type === 'VOICEBOT') {
        const hasNext = (node.options ?? []).some((o) => o.key === 'next');
        const hasEscalate = (node.options ?? []).some((o) => o.key === 'escalate');
        const hasFallback = (node.options ?? []).some((o) => o.key === 'fallback');
        if (!hasNext)
          warnings.push(
            this.transloco.translate('supervisor.ivr.warnVoicebotNoNext', { id: node.node_id }),
          );
        if (!hasEscalate)
          warnings.push(
            this.transloco.translate('supervisor.ivr.warnVoicebotNoEscalate', { id: node.node_id }),
          );
        if (!hasFallback)
          warnings.push(
            this.transloco.translate('supervisor.ivr.warnVoicebotNoFallback', { id: node.node_id }),
          );
      }
      for (const opt of node.options ?? []) {
        if (opt.next_node_id && !nodeIds.has(opt.next_node_id)) {
          warnings.push(
            this.transloco.translate('supervisor.ivr.warnOptionBroken', {
              key: opt.key,
              id: node.node_id,
            }),
          );
        }
      }
    }

    // Reachability check from entry node
    if (def.entry_node_id && nodeIds.has(def.entry_node_id)) {
      const visited = new Set<string>();
      const queue = [def.entry_node_id];
      while (queue.length > 0) {
        const id = queue.shift()!;
        if (visited.has(id)) continue;
        visited.add(id);
        const node = def.nodes.find((n) => n.node_id === id);
        if (node) {
          for (const opt of node.options ?? []) {
            if (opt.next_node_id && !visited.has(opt.next_node_id)) {
              queue.push(opt.next_node_id);
            }
          }
        }
      }
      const unreachable = def.nodes.filter((n) => !visited.has(n.node_id));
      for (const n of unreachable) {
        warnings.push(
          this.transloco.translate('supervisor.ivr.warnUnreachable', {
            label: n.prompt?.slice(0, 30) ?? n.node_id,
          }),
        );
      }
    }

    return warnings;
  }

  // ─── Node management ────────────────────────────────────────────────────────

  onToolbarDragStart(event: DragEvent, type: IvrNodeType): void {
    event.dataTransfer?.setData('node-type', type);
  }

  onCanvasDragOver(event: DragEvent): void {
    event.preventDefault();
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = 'copy';
    }
  }

  onCanvasDrop(event: DragEvent): void {
    event.preventDefault();
    const type = event.dataTransfer?.getData('node-type') as IvrNodeType | undefined;
    if (!type) return;

    const canvas = this.canvasRef()?.nativeElement;
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const x = event.clientX - rect.left + canvas.scrollLeft - NODE_WIDTH / 2;
    const y = event.clientY - rect.top + canvas.scrollTop - 40;

    const newNode: IvrNodeUI = {
      node_id: this.ivrService.generateNodeId(),
      type,
      prompt: type === 'SET' || type === 'IF' || type === 'SWITCH' ? undefined : '',
      // PLAY_AUDIO always has a single "next" output; MENU/COLLECT_DTMF start with empty options
      // SET has a single "next" output; IF has fixed true/false; SWITCH starts with "default"
      options:
        type === 'PLAY_AUDIO'
          ? [{ key: 'next', next_node_id: '' }]
          : type === 'COLLECT_DTMF'
            ? [
                { key: 'success', next_node_id: '' },
                { key: 'timeout', next_node_id: '' },
                { key: 'no-input', next_node_id: '' },
              ]
            : type === 'SET'
              ? [{ key: 'next', next_node_id: '' }]
              : type === 'IF'
                ? [
                    { key: 'true', next_node_id: '' },
                    { key: 'false', next_node_id: '' },
                  ]
                : type === 'SWITCH'
                  ? [{ key: 'default', next_node_id: '' }]
                  : type === 'VOICEBOT'
                    ? [
                        { key: 'next', next_node_id: '' },
                        { key: 'escalate', next_node_id: '' },
                        { key: 'fallback', next_node_id: '' },
                      ]
                    : [],
      x: Math.max(0, x),
      y: Math.max(0, y),
      timeout_seconds: type === 'MENU' || type === 'COLLECT_DTMF' ? 10 : undefined,
      max_retries: type === 'MENU' || type === 'COLLECT_DTMF' ? 3 : undefined,
      ...(type === 'COLLECT_DTMF'
        ? {
            variable_name: '',
            min_digits: 1,
            max_digits: 4,
            finish_on_key: '#',
          }
        : {}),
      ...(type === 'SET' ? { variable_name: '', value: '' } : {}),
      ...(type === 'IF' ? { variable_name: '', operator: 'EQUALS', compare_value: '' } : {}),
      ...(type === 'SWITCH' ? { variable_name: '' } : {}),
    };

    this.definition.update((def) => ({
      ...def,
      nodes: [...def.nodes, newNode],
      entry_node_id: def.nodes.length === 0 ? newNode.node_id : def.entry_node_id,
    }));
    this.selectedNodeId.set(newNode.node_id);
  }

  onNodeMouseDown(event: MouseEvent, nodeId: string): void {
    // Don't start drag when clicking buttons/ports
    const target = event.target as HTMLElement;
    if (target.closest('button') || target.closest('.node-port')) return;

    // Always stop propagation so canvas click doesn't cancel the connection
    event.stopPropagation();

    const connecting = this.connectingFrom();
    if (connecting) {
      if (connecting.nodeId !== nodeId) {
        // Complete the connection on mousedown – more reliable than click
        // because click can be suppressed if the mouse moved slightly
        this.definition.update((def) => ({
          ...def,
          nodes: def.nodes.map((n) => {
            if (n.node_id !== connecting.nodeId) return n;
            return {
              ...n,
              options: (n.options ?? []).map((opt) =>
                opt.key === connecting.optionKey ? { ...opt, next_node_id: nodeId } : opt,
              ),
            };
          }),
        }));
      }
      this.connectingFrom.set(null);
      this.selectedNodeId.set(nodeId);
      return;
    }

    const node = this.definition().nodes.find((n) => n.node_id === nodeId);
    if (!node) return;

    this.dragState = {
      nodeId,
      startMouseX: event.clientX,
      startMouseY: event.clientY,
      startNodeX: node.x,
      startNodeY: node.y,
    };
    this.selectedNodeId.set(nodeId);
  }

  onCanvasMouseMove(event: MouseEvent): void {
    if (!this.dragState) return;
    const dx = event.clientX - this.dragState.startMouseX;
    const dy = event.clientY - this.dragState.startMouseY;
    const newX = Math.max(0, this.dragState.startNodeX + dx);
    const newY = Math.max(0, this.dragState.startNodeY + dy);

    this.definition.update((def) => ({
      ...def,
      nodes: def.nodes.map((n) =>
        n.node_id === this.dragState!.nodeId ? { ...n, x: newX, y: newY } : n,
      ),
    }));
  }

  onCanvasMouseUp(): void {
    this.dragState = null;
  }

  onCanvasClick(): void {
    if (this.connectingFrom()) {
      this.connectingFrom.set(null);
    }
    this.selectedNodeId.set(null);
  }

  onNodeClick(event: Event, nodeId: string): void {
    event.stopPropagation();
    const connecting = this.connectingFrom();
    if (connecting && connecting.nodeId !== nodeId) {
      // Complete the connection
      this.definition.update((def) => ({
        ...def,
        nodes: def.nodes.map((n) => {
          if (n.node_id !== connecting.nodeId) return n;
          return {
            ...n,
            options: (n.options ?? []).map((opt) =>
              opt.key === connecting.optionKey ? { ...opt, next_node_id: nodeId } : opt,
            ),
          };
        }),
      }));
      this.connectingFrom.set(null);
      return;
    }
    this.selectedNodeId.set(nodeId);
  }

  onOutputPortClick(event: MouseEvent, nodeId: string, optionKey: string): void {
    event.stopPropagation();
    const current = this.connectingFrom();
    if (current?.nodeId === nodeId && current?.optionKey === optionKey) {
      this.connectingFrom.set(null);
    } else {
      this.connectingFrom.set({ nodeId, optionKey });
    }
  }

  onDeleteNode(event: MouseEvent, nodeId: string): void {
    event.stopPropagation();
    this.definition.update((def) => {
      const nodes = def.nodes.filter((n) => n.node_id !== nodeId);
      // Remove connections pointing to deleted node
      const cleaned = nodes.map((n) => ({
        ...n,
        options: (n.options ?? []).map((opt) =>
          opt.next_node_id === nodeId ? { ...opt, next_node_id: '' } : opt,
        ),
      }));
      return {
        nodes: cleaned,
        entry_node_id:
          def.entry_node_id === nodeId ? (cleaned[0]?.node_id ?? '') : def.entry_node_id,
      };
    });
    if (this.selectedNodeId() === nodeId) {
      this.selectedNodeId.set(null);
    }
    if (this.connectingFrom()?.nodeId === nodeId) {
      this.connectingFrom.set(null);
    }
  }

  // ─── Property panel ─────────────────────────────────────────────────────────

  updateSelectedNodeProp(prop: keyof IvrNodeUI, value: unknown): void {
    const id = this.selectedNodeId();
    if (!id) return;
    this.definition.update((def) => ({
      ...def,
      nodes: def.nodes.map((n) => (n.node_id === id ? { ...n, [prop]: value } : n)),
    }));
  }

  setEntryNode(): void {
    const id = this.selectedNodeId();
    if (!id) return;
    this.definition.update((def) => ({ ...def, entry_node_id: id }));
  }

  addOption(): void {
    const id = this.selectedNodeId();
    if (!id) return;
    const node = this.selectedNode();
    // SET, IF and VOICEBOT have fixed routing – cannot add options
    if (node?.type === 'SET' || node?.type === 'IF' || node?.type === 'VOICEBOT') return;
    // For SWITCH: insert new option before the "default" entry
    if (node?.type === 'SWITCH') {
      const options = node.options ?? [];
      const defaultIndex = options.findIndex((o) => o.key === 'default');
      const newOpt: IvrOption = { key: '', next_node_id: '' };
      const updated =
        defaultIndex >= 0
          ? [...options.slice(0, defaultIndex), newOpt, ...options.slice(defaultIndex)]
          : [...options, newOpt];
      this.definition.update((def) => ({
        ...def,
        nodes: def.nodes.map((n) => (n.node_id === id ? { ...n, options: updated } : n)),
      }));
      return;
    }
    const newOpt: IvrOption = { key: '', next_node_id: '' };
    this.definition.update((def) => ({
      ...def,
      nodes: def.nodes.map((n) =>
        n.node_id === id ? { ...n, options: [...(n.options ?? []), newOpt] } : n,
      ),
    }));
  }

  updateOption(optionIndex: number, field: keyof IvrOption, value: string): void {
    const id = this.selectedNodeId();
    if (!id) return;
    this.definition.update((def) => ({
      ...def,
      nodes: def.nodes.map((n) => {
        if (n.node_id !== id) return n;
        const options = (n.options ?? []).map((opt, i) =>
          i === optionIndex ? { ...opt, [field]: value } : opt,
        );
        return { ...n, options };
      }),
    }));
  }

  removeOption(optionIndex: number): void {
    const id = this.selectedNodeId();
    if (!id) return;
    // Guard: "default" option in SWITCH cannot be removed
    const node = this.selectedNode();
    if (node?.type === 'SWITCH') {
      const opt = node.options?.[optionIndex];
      if (opt?.key === 'default') return;
    }
    this.definition.update((def) => ({
      ...def,
      nodes: def.nodes.map((n) => {
        if (n.node_id !== id) return n;
        return { ...n, options: (n.options ?? []).filter((_, i) => i !== optionIndex) };
      }),
    }));
  }

  // ─── Audio upload (mock) ─────────────────────────────────────────────────────

  triggerAudioUpload(nodeId: string): void {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.mp3,.wav';
    input.onchange = (e: Event) => {
      const file = (e.target as HTMLInputElement).files?.[0];
      if (!file) return;
      if (file.size > 10 * 1024 * 1024) {
        this.notifications.error(
          this.transloco.translate('supervisor.ivrEditor.errorFileTooLarge'),
        );
        return;
      }
      const ext = file.name.split('.').pop()?.toLowerCase();
      if (ext !== 'mp3' && ext !== 'wav') {
        this.notifications.error(this.transloco.translate('supervisor.ivrEditor.errorFileFormat'));
        return;
      }
      this.simulateUpload(nodeId, file.name);
    };
    input.click();
  }

  private simulateUpload(nodeId: string, fileName: string): void {
    this.uploadInProgress.set(nodeId);
    this.uploadProgress.set(0);
    const interval = setInterval(() => {
      this.uploadProgress.update((p) => {
        if (p >= 100) {
          clearInterval(interval);
          this.uploadInProgress.set(null);
          const audioId = `mock-audio://${fileName}`;
          this.definition.update((def) => ({
            ...def,
            nodes: def.nodes.map((n) => (n.node_id === nodeId ? { ...n, audio_id: audioId } : n)),
          }));
          if (this.selectedNodeId() === nodeId) {
            // Trigger re-render of panel
            this.selectedNodeId.set(nodeId);
          }
          this.notifications.success(
            this.transloco.translate('supervisor.ivrEditor.uploadSuccess', { name: fileName }),
          );
          return 100;
        }
        return p + 20;
      });
    }, 300);
  }

  // ─── SVG connections ─────────────────────────────────────────────────────────

  private computeBezierPath(fromNode: IvrNodeUI, optionKey: string, toNode: IvrNodeUI): string {
    const optionIndex = (fromNode.options ?? []).findIndex((o) => o.key === optionKey);
    const fromX = fromNode.x + NODE_WIDTH;
    const fromY = fromNode.y + NODE_HEIGHT_BASE / 2 + optionIndex * 28;
    const toX = toNode.x;
    const toY = toNode.y + NODE_HEIGHT_BASE / 2;

    const cp1x = fromX + 60;
    const cp1y = fromY;
    const cp2x = toX - 60;
    const cp2y = toY;

    return `M ${fromX} ${fromY} C ${cp1x} ${cp1y}, ${cp2x} ${cp2y}, ${toX} ${toY}`;
  }

  isConnectingFromOption(nodeId: string, optionKey: string): boolean {
    const cf = this.connectingFrom();
    return cf?.nodeId === nodeId && cf?.optionKey === optionKey;
  }

  // ─── Utils ───────────────────────────────────────────────────────────────────

  getNodeHeight(node: IvrNodeUI): number {
    return NODE_HEIGHT_BASE + Math.max(0, (node.options?.length ?? 0) - 1) * 28;
  }

  canvasSvgWidth = 3000;
  canvasSvgHeight = 2000;

  copyJsonToClipboard(): void {
    navigator.clipboard.writeText(this.apiDefinitionJson()).then(
      () =>
        this.notifications.success(this.transloco.translate('supervisor.ivrEditor.successCopy')),
      () => this.notifications.error(this.transloco.translate('supervisor.ivrEditor.errorCopy')),
    );
  }

  goBack(): void {
    this.router.navigate(['/supervisor/ivr']);
  }

  trackByNodeId(_index: number, node: IvrNodeUI): string {
    return node.node_id;
  }

  trackByIndex(index: number): number {
    return index;
  }

  nodeTypesList(): { type: IvrNodeType; label: string }[] {
    return this.nodeTypes.map((t) => ({ type: t, label: this.getNodeLabel(t) }));
  }

  otherNodes(currentNodeId: string): IvrNodeUI[] {
    return this.definition().nodes.filter((n) => n.node_id !== currentNodeId);
  }

  getNodeShortLabel(nodeId: string): string {
    const node = this.definition().nodes.find((n) => n.node_id === nodeId);
    if (!node) return nodeId.slice(0, 12) + '…';
    const type = this.getNodeLabel(node.type);
    const prompt = node.prompt ? ': ' + node.prompt.slice(0, 15) : '';
    return type + prompt;
  }
}
