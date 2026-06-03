export interface KycPendingItem {
  userId: string;
  status: string;
  uploadedAt: string;
  originalFilename: string | null;
  contentType: string | null;
  sizeBytes: number;
}

export type KycDecision = 'VERIFIED' | 'REJECTED';
