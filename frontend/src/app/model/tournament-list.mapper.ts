import { TournamentListItem, TournamentStatus } from './tournament';

export interface TournamentSummaryApi {
  id: string;
  name: string;
  type?: string;
  status: TournamentStatus;
  registeredPlayers: number;
  maxPlayers: number;
  buyIn: number;
  prizePool: number;
  currentLevel: number;
  startingChips?: number;
  smallBlind?: number;
  bigBlind?: number;
}

export function mapTournamentListFromApi(items: TournamentSummaryApi[]): TournamentListItem[] {
  return items.map(item => ({
    id: item.id,
    name: item.name,
    status: item.status,
    buyIn: item.buyIn,
    startingChips: item.startingChips ?? 1500,
    currentLevel: item.currentLevel,
    registeredCount: item.registeredPlayers,
    maxPlayers: item.maxPlayers,
    prizePool: item.prizePool,
    smallBlind: item.smallBlind ?? 10,
    bigBlind: item.bigBlind ?? 20
  }));
}
