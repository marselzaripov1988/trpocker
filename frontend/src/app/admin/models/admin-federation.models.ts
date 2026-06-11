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
  /** House commission on the crypto prize pool, in basis points (0–2000 = 0–20%). */
  feeBasisPoints: number;
  shardsPending: number;
  shardsRegistering: number;
  shardsReady: number;
  shardsRunning: number;
  shardsCompleted: number;
  /** Prize config: shard-winner qualifier (ppm of the pool), non-champion final-table place shares (CSV of
   *  basis points, index 0 = 2nd place) and the rest-of-table bps. The champion takes the remainder. */
  shardWinnerPpm: number;
  finalTablePlaceBps: string;
  finalTableRestBps: number;
  /** Real-money config: per-player buy-in + asset (null/absent for play-money). */
  cryptoBuyInAmount?: number | null;
  cryptoBuyInAsset?: string | null;
  /** Isolated-custody variant: each buy-in is paid on-chain into a dedicated per-player Solana wallet. */
  isolatedWalletsEnabled: boolean;
}

/** Mirrors the backend PrizeConfigRequest (any field omitted falls back to the global default). */
export interface PrizeConfigRequest {
  shardWinnerPpm: number;
  finalTablePlaceBps: string;
  finalTableRestBps: number;
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
  /** House commission on the crypto prize pool, in basis points (0–2000 = 0–20%). 0/absent = no fee. */
  feeBasisPoints?: number;
  /** Isolated custody: each buy-in is paid on-chain into a dedicated per-player wallet (requires USDT_SOL). */
  isolatedWalletsEnabled?: boolean;
}

/** One offline-generated dedicated wallet to import — mirrors FederationWalletImportRequest.Entry; the chunk
 *  file the offline generator writes is `{ wallets: WalletImportEntry[] }`, pasteable as-is. */
export interface WalletImportEntry {
  derivationIndex: number;
  ownerPubkey: string;
  address: string;
}

/** Read view of an isolated-custody refund (mirrors FederationRefundResponse). */
export interface FederationRefund {
  id: string;
  federationId: string;
  walletId: string;
  playerId: string;
  asset: string;
  grossAmount: number;
  feeAmount: number;
  netAmount: number;
  toAddress: string | null;
  status: string;
  txId: string | null;
}

/** Unsigned ATA batch (create/close) for the air-gapped signer (mirrors SolAtaBatchUnsignedDto). */
export interface SolAtaBatchUnsigned {
  federationId: string;
  operation: 'create' | 'close';
  messageBase64: string;
  feePayer: string;
  walletIds: string[];
  signers: { pubkey: string; derivationIndex: number | null }[];
}

/** Unsigned refund tx for the air-gapped signer (mirrors SolRefundUnsignedDto). */
export interface SolRefundUnsigned {
  refundId: string;
  messageBase64: string;
  feePayer: string;
  authority: string;
  authorityDerivationIndex: number;
  destAta: string;
  amount: number;
  createsDestAta: boolean;
}
