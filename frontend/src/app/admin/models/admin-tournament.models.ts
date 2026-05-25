export type AdminTournamentType =
  | 'FREEZEOUT'
  | 'REBUY'
  | 'BOUNTY'
  | 'SIT_AND_GO'
  | 'MULTI_TABLE'
  | 'PYRAMID';

export interface CreateTournamentAdminRequest {
  name: string;
  type: AdminTournamentType;
  startingChips: number;
  minPlayers: number;
  maxPlayers: number;
  buyIn: number;
  blindStructureType?: string;
}

export interface PyramidRunResponse {
  tournamentId: string;
  championId: string;
  roundsPlayed: number;
  finalStatus: string;
}

export const TOURNAMENT_TYPE_OPTIONS: { value: AdminTournamentType; label: string }[] = [
  { value: 'FREEZEOUT', label: 'Freezeout (MTT)' },
  { value: 'SIT_AND_GO', label: 'Sit & Go' },
  { value: 'MULTI_TABLE', label: 'Multi-table' },
  { value: 'REBUY', label: 'Rebuy' },
  { value: 'BOUNTY', label: 'Bounty' },
  { value: 'PYRAMID', label: 'Pyramid survival' }
];
