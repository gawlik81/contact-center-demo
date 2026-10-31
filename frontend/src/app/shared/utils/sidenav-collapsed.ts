import { UserRole } from '../../core/models/jwt-payload.model';
import { BREAKPOINT_XXL } from '../styles/breakpoints';

/**
 * localStorage key of the user's own choice. `_v2` on purpose: the old key was written on every
 * mount (also as 'false', a value nobody chose), so reading it would freeze every existing
 * browser on "expanded" and the agent rail default would never apply. It is written ONLY when
 * the user toggles the sidenav.
 */
export const SIDENAV_COLLAPSED_KEY = 'cc_sidenav_collapsed_v2';
const LEGACY_SIDENAV_COLLAPSED_KEY = 'cc_sidenav_collapsed';

/**
 * Collapsed state at first render. Shared by the sidenav and the shell so both agree
 * synchronously (no frame with the wide sidenav followed by an animated jump to the rail).
 * A stored choice wins; otherwise an agent on a window <= `xxl` starts as the icon rail.
 */
export function initialSidenavCollapsed(role: UserRole | null, win: Window | undefined): boolean {
  try {
    // The legacy key carries no information; drop it so it cannot confuse anything later.
    win?.localStorage.removeItem(LEGACY_SIDENAV_COLLAPSED_KEY);
    const stored = win?.localStorage.getItem(SIDENAV_COLLAPSED_KEY) ?? null;
    if (stored !== null) return stored === 'true';
  } catch {
    // Storage unavailable: fall through to the derived default
  }
  return role === 'AGENT' && !!win && win.innerWidth <= BREAKPOINT_XXL;
}

export function storeSidenavCollapsed(win: Window | undefined, collapsed: boolean): void {
  try {
    win?.localStorage.setItem(SIDENAV_COLLAPSED_KEY, String(collapsed));
  } catch {
    // Storage unavailable: the choice just does not persist
  }
}
