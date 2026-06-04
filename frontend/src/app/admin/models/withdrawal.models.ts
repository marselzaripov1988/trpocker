export interface AdminWithdrawal {
  id: string;
  userId: string;
  asset: string;
  network: string;
  toAddress: string;
  amount: number | string;
  status: string;
  txId: string | null;
  reviewedBy: string | null;
  rejectionReason: string | null;
  createdAt: string;
}

export interface WithdrawalSigningRequest {
  withdrawalId: string;
  asset: string;
  network: string;
  toAddress: string;
  amount: number | string;
  createdAt: string;
}
