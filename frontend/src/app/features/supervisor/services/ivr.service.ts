import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import {
  CreateIvrRequest,
  IvrDefinitionUI,
  IvrNodeUI,
  IvrResponse,
  UpdateIvrRequest,
} from '../models/ivr.model';

const POSITIONS_KEY_PREFIX = 'ivr:positions:';
const DEFAULT_NODE_SPACING_X = 260;
const DEFAULT_NODE_SPACING_Y = 160;

@Injectable({ providedIn: 'root' })
export class IvrService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/ivr`;

  getIvrList(): Observable<IvrResponse[]> {
    return this.http.get<IvrResponse[]>(this.baseUrl);
  }

  getIvr(ivrId: string): Observable<IvrResponse> {
    return this.http.get<IvrResponse>(`${this.baseUrl}/${ivrId}`);
  }

  createIvr(req: CreateIvrRequest): Observable<IvrResponse> {
    return this.http.post<IvrResponse>(this.baseUrl, req);
  }

  updateIvr(ivrId: string, req: UpdateIvrRequest): Observable<IvrResponse> {
    return this.http.patch<IvrResponse>(`${this.baseUrl}/${ivrId}`, req);
  }

  deleteIvr(ivrId: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${ivrId}`);
  }

  activateIvr(ivrId: string): Observable<IvrResponse> {
    return this.http.post<IvrResponse>(`${this.baseUrl}/${ivrId}/activate`, {});
  }

  deactivateIvr(ivrId: string): Observable<IvrResponse> {
    return this.http.post<IvrResponse>(`${this.baseUrl}/${ivrId}/deactivate`, {});
  }

  /**
   * Loads node UI positions from localStorage and merges with definition nodes.
   */
  loadPositions(ivrId: string, nodes: IvrNodeUI[]): IvrNodeUI[] {
    const key = `${POSITIONS_KEY_PREFIX}${ivrId}`;
    try {
      const raw = localStorage.getItem(key);
      if (!raw) return this.assignDefaultPositions(nodes);
      const positions: Record<string, { x: number; y: number }> = JSON.parse(raw);
      return nodes.map((node) => ({
        ...node,
        x: positions[node.node_id]?.x ?? node.x,
        y: positions[node.node_id]?.y ?? node.y,
      }));
    } catch {
      return this.assignDefaultPositions(nodes);
    }
  }

  /**
   * Saves current node positions to localStorage.
   */
  savePositions(ivrId: string, nodes: IvrNodeUI[]): void {
    const key = `${POSITIONS_KEY_PREFIX}${ivrId}`;
    const positions: Record<string, { x: number; y: number }> = {};
    nodes.forEach((n) => {
      positions[n.node_id] = { x: n.x, y: n.y };
    });
    try {
      localStorage.setItem(key, JSON.stringify(positions));
    } catch {
      // Ignore storage errors
    }
  }

  /**
   * Clears saved positions for an IVR.
   */
  clearPositions(ivrId: string): void {
    const key = `${POSITIONS_KEY_PREFIX}${ivrId}`;
    try {
      localStorage.removeItem(key);
    } catch {
      // Ignore
    }
  }

  /**
   * Converts IvrResponse definition to UI definition with positions.
   */
  toUIDefinition(ivrId: string, response: IvrResponse): IvrDefinitionUI {
    const nodesWithDefaults: IvrNodeUI[] = response.definition.nodes.map((n) => ({
      ...n,
      // PLAY_AUDIO always needs a "next" output option; add it if missing (e.g. legacy data)
      options:
        n.type === 'PLAY_AUDIO' && (!n.options || n.options.length === 0)
          ? [{ key: 'next', next_node_id: '' }]
          : (n.options ?? []),
      x: 0,
      y: 0,
    }));
    const positioned = this.loadPositions(ivrId, nodesWithDefaults);
    return {
      nodes: positioned,
      entry_node_id: response.definition.entry_node_id,
    };
  }

  /**
   * Converts UI definition back to API definition (strips x/y).
   */
  toApiDefinition(def: IvrDefinitionUI): { nodes: unknown[]; entry_node_id: string } {
    return {
      entry_node_id: def.entry_node_id,
      nodes: def.nodes.map(({ x: _x, y: _y, ...rest }) => rest),
    };
  }

  private assignDefaultPositions(nodes: IvrNodeUI[]): IvrNodeUI[] {
    const cols = 3;
    return nodes.map((node, index) => ({
      ...node,
      x: 40 + (index % cols) * DEFAULT_NODE_SPACING_X,
      y: 40 + Math.floor(index / cols) * DEFAULT_NODE_SPACING_Y,
    }));
  }

  generateNodeId(): string {
    return `node-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
  }
}
