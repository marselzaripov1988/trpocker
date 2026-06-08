import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';

/**
 * Resolves and caches player avatars (by user id) for table seats. Avatars are NOT part of the hot game-state
 * payload — the table calls {@link ensure} with the seated players' user ids and this service batch-fetches the
 * missing ones from `GET /v1/users/avatars`, caching results (including "no avatar", to avoid re-querying).
 * The cache is exposed as a signal so seats re-render when avatars arrive.
 */
@Injectable({ providedIn: 'root' })
export class PlayerAvatarService {
  private readonly http = inject(HttpClient);
  private readonly cache = new Map<string, string>();
  private readonly inFlight = new Set<string>();

  /** userId → avatar value (emoji or image URL); '' means "fetched, none set". */
  private readonly _avatars = signal<Record<string, string>>({});
  readonly avatars = this._avatars.asReadonly();

  /** Ensure avatars for these user ids are loaded (fetches only the ones not cached / not already in flight). */
  ensure(userIds: readonly (string | null | undefined)[]): void {
    const missing = Array.from(new Set(
      userIds.filter((id): id is string => !!id && !this.cache.has(id) && !this.inFlight.has(id))
    ));
    if (missing.length === 0) {
      return;
    }
    missing.forEach(id => this.inFlight.add(id));
    this.http.get<Record<string, string>>('/api/v1/users/avatars', { params: { ids: missing.join(',') } })
      .subscribe({
        next: (map) => {
          missing.forEach(id => {
            this.cache.set(id, map?.[id] ?? '');
            this.inFlight.delete(id);
          });
          this._avatars.set(Object.fromEntries(this.cache));
        },
        error: () => missing.forEach(id => this.inFlight.delete(id))
      });
  }

  /** Cached avatar value for a user id ('' if none/unknown). Read via the `avatars` signal for reactivity. */
  avatarFor(userId: string | null | undefined): string {
    return userId ? (this._avatars()[userId] ?? '') : '';
  }
}
