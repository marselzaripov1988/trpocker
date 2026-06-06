export type FederationStatus =
  | 'REGISTERING'
  | 'SHARDS_RUNNING'
  | 'AWAITING_FINAL'
  | 'FINAL_SCHEDULED'
  | 'FINAL_RUNNING'
  | 'COMPLETED'
  | 'CANCELLED';

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

/** Mirrors the backend CreateFederationRequest. */
export interface CreateFederationRequest {
  name: string;
  startingPlayers: number;
  shardSize: number;
  registrationDeadline?: string | null;
  /** Optional real-money buy-in; with `buyUpEnabled` it's required (seat buy-outs charge it). */
  buyInAmount?: number | null;
  buyInAsset?: string | null;
  /** Buy-up variant: each shard is a buy-up pyramid (players can buy higher-level / final seats). */
  buyUpEnabled?: boolean;
}
