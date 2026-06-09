import { Routes, Route } from '@angular/router';
import { routes } from './app.routes';

/**
 * Regression guard for the lazy-loaded route graph. Every `loadComponent` / `loadChildren` chunk is actually
 * imported and resolved here. This is the unit-test analogue of navigating to each route: it catches the class
 * of bugs that only surface when a lazy chunk is first evaluated — circular imports, a missing browser polyfill
 * (e.g. sockjs-client's `global`), or "Cannot access X before initialization" — none of which the type-checker
 * or a normal component spec would surface.
 */
describe('app routes (lazy graph resolves)', () => {
  const flatten = (rs: Routes): Route[] =>
    rs.flatMap(r => [r, ...(r.children ? flatten(r.children) : [])]);

  const all = flatten(routes);

  it('exposes a non-trivial route table', () => {
    expect(routes.length).toBeGreaterThan(5);
  });

  it('every lazy loadComponent resolves to a standalone component', async () => {
    const lazy = all.filter(r => r.loadComponent);
    expect(lazy.length).toBeGreaterThan(0);

    for (const r of lazy) {
      let cmp: unknown;
      try {
        cmp = await (r.loadComponent as () => Promise<unknown>)();
      } catch (e) {
        throw new Error(`route '${r.path}' loadComponent threw: ${(e as Error).message}`);
      }
      expect(typeof cmp).toBe('function');
      // standalone components carry the compiled ɵcmp definition
      expect((cmp as { ɵcmp?: unknown }).ɵcmp).toBeTruthy();
    }
  });

  it('every lazy loadChildren resolves to a routes array', async () => {
    const lazy = all.filter(r => r.loadChildren);
    expect(lazy.length).toBeGreaterThan(0);

    for (const r of lazy) {
      let children: unknown;
      try {
        children = await (r.loadChildren as () => Promise<unknown>)();
      } catch (e) {
        throw new Error(`route '${r.path}' loadChildren threw: ${(e as Error).message}`);
      }
      expect(Array.isArray(children)).toBe(true);
      expect((children as unknown[]).length).toBeGreaterThan(0);
    }
  });
});
