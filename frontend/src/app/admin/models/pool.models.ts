export interface PoolAssetCount {
  asset: string;
  free: number;
  assigned: number;
}

export interface PoolStatus {
  assets: PoolAssetCount[];
}

/** One public deposit address from an offline-generated batch (no private material). */
export interface PoolEntry {
  asset: string;
  derivationIndex: number;
  address: string;
}

export interface PoolImportResult {
  imported: number;
  skipped: number;
}
