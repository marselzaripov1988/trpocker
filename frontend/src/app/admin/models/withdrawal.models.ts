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

/** Assembled-from-the-node unsigned ETH/ERC-20 tx (all quantities 0x-hex) for the offline signer. */
export interface EthUnsignedTx {
  withdrawalId: string;
  asset: string;
  chainId: number;
  from: string;
  nonce: string;
  gasPrice: string;
  gasLimit: number;
  to: string;
  value: string;
  data: string;
}

export interface BtcTxInput { txid: string; vout: number; valueSat: number; scriptPubKey: string; }
export interface BtcTxOutput { valueSat: number; scriptPubKey: string; label: string; }

/** Assembled-from-the-node unsigned BTC (P2WPKH) tx (UTXO inputs + outputs + fee) for the offline signer. */
export interface BtcUnsignedTx {
  withdrawalId: string;
  network: string;
  version: number;
  locktime: number;
  feeSat: number;
  inputs: BtcTxInput[];
  outputs: BtcTxOutput[];
}

export type ChainUnsignedTx = EthUnsignedTx | BtcUnsignedTx;

export interface EthConfirmation {
  txId: string;
  mined: boolean;
  success: boolean;
  confirmations: number;
  confirmed: boolean;
}

export interface BtcConfirmation {
  txId: string;
  confirmations: number;
  confirmed: boolean;
}

export type ChainConfirmation = EthConfirmation | BtcConfirmation;
