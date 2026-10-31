/**
 * TypeScript mirror of the steps of `_breakpoints.scss` that logic (not CSS) depends on.
 * `breakpoints.spec.ts` reads the partial and fails when these values drift, so change both
 * together. Everything else in the scale is SCSS-only.
 */
export const BREAKPOINT_XL = 1280; // sidenav becomes a static column
export const BREAKPOINT_XXL = 1440; // tight laptop: agent sidenav starts as the icon rail
