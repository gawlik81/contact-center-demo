import { BREAKPOINT_XL, BREAKPOINT_XXL } from './breakpoints';

// The workspace has no Node typings, so the builtin is loaded through a variable specifier.
// `ng test` runs from the workspace root (frontend/).
const FS = 'node:fs';
const PATH = 'node:path';

async function readPartial(): Promise<string> {
  const fs = (await import(/* @vite-ignore */ FS)) as {
    readFileSync: (p: string, enc: string) => string;
  };
  const path = (await import(/* @vite-ignore */ PATH)) as { join: (...p: string[]) => string };
  const cwd = (globalThis as unknown as { process: { cwd: () => string } }).process.cwd();
  return fs.readFileSync(path.join(cwd, 'src/app/shared/styles/_breakpoints.scss'), 'utf8');
}

/** Reads a step of the `$widths` map from the partial. */
function scssWidth(scss: string, name: string): number {
  const match = new RegExp(`^\\s*${name}:\\s*(\\d+)px`, 'm').exec(scss);
  if (!match) throw new Error(`Step "${name}" not found in _breakpoints.scss`);
  return Number(match[1]);
}

describe('breakpoints.ts mirrors _breakpoints.scss', () => {
  it('xl', async () => expect(BREAKPOINT_XL).toBe(scssWidth(await readPartial(), 'xl')));
  it('xxl', async () => expect(BREAKPOINT_XXL).toBe(scssWidth(await readPartial(), 'xxl')));
});
