export type FederationStatus =
  | 'REGISTERING'
  | 'SHARDS_RUNNING'
  | 'AWAITING_FINAL'
  | 'FINAL_SCHEDULED'
  | 'FINAL_RUNNING'
  | 'COMPLETED'
  | 'CANCELLED';

export type ShardStatus = 'PENDING' | 'REGISTERING' | 'READY' | 'RUNNING' | 'COMPLETED' | 'CANCELLED';

/** Mirrors the backend FederationDetailResponse. */
export interface FederationDetail {
  id: string;
  name: string;
  status: FederationStatus;
  shardSize: number;
  shardCount: number;
  seatsPerTable: number;
  registeredPlayers: number;
  registrationDeadline?: string | null;
  finalScheduledStart?: string | null;
  finalTournamentId?: string | null;
  championPlayerId?: string | null;
  shardsPending: number;
  shardsRegistering: number;
  shardsReady: number;
  shardsRunning: number;
  shardsCompleted: number;
}

/** Mirrors the backend FederationRegistrationResponse. */
export interface FederationRegistration {
  federationId: string;
  playerId: string;
  shardIndex: number;
  shardStatus: ShardStatus;
  federationStatus: FederationStatus;
}

/** A buyable final seat (buy-up federation): claiming it closes the empty shard at {@code shardIndex}. */
export interface FinalSeat {
  shardIndex: number;
  price: number;
  asset: string;
}
