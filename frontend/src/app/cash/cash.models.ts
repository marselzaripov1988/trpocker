/** TypeScript mirrors of the backend cash-table DTOs (com.truholdem.dto.Cash*). */

export type PlayerAction = 'FOLD' | 'CHECK' | 'CALL' | 'BET' | 'RAISE' | 'ALL_IN';
export type CashSeatStatus = 'ACTIVE' | 'SITTING_OUT' | 'LEAVING' | 'LEFT';

/** Mirrors CashTableResponse. */
export interface CashTable {
  id: string;
  name: string;
  asset: string;
  smallBlind: number;
  bigBlind: number;
  minBuyIn: number;
  maxBuyIn: number;
  maxSeats: number;
  rakeBasisPoints: number;
  rakeCap: number;
  seatedPlayers: number;
  active: boolean;
}

/** Mirrors CashSeatResponse. */
export interface CashSeat {
  seatNumber: number;
  playerName: string;
  stack: number;
  status: CashSeatStatus;
}

/** Mirrors CashHandResponse — the current hand from the caller's perspective. */
export interface CashHand {
  inProgress: boolean;
  handNumber: number;
  phase: string | null;
  pot: number;
  currentActorName: string | null;
  communityCards: readonly string[];
  yourCards: readonly string[];
}

/** Mirrors CashTableStateResponse. */
export interface CashTableState {
  table: CashTable;
  seats: readonly CashSeat[];
  hand: CashHand;
}

/** Mirrors SitDownResponse. */
export interface SitDownResult {
  seatNumber: number;
  playerName: string;
  stack: number;
  status: CashSeatStatus;
}

/** Mirrors CashActionResponse. */
export interface CashActionResult {
  handComplete: boolean;
  totalRake: number;
  cashedOut: readonly string[];
}

/** Mirrors CashLeaveResponse. */
export interface CashLeaveResult {
  cashedOutNow: boolean;
  amount: number;
}
