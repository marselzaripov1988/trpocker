/**
 * A seat to create when starting a game (the request shape sent to the backend's /poker/start).
 * Kept in its own module so services (e.g. PlayerService) can reference the type without importing
 * the RegisterPlayersComponent — that back-edge formed a circular import that broke lazy-chunk init.
 */
export interface PlayerInfo {
  name: string;
  startingChips: number;
  isBot: boolean;
}
