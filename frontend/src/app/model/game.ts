import { Card } from "./card";
import { Player } from "./player";

export type GamePhase = 'PRE_FLOP' | 'FLOP' | 'TURN' | 'RIVER' | 'SHOWDOWN';
export type HandLifecycleState = 'IN_PROGRESS' | 'HAND_COMPLETED' | 'RESULT_DELAY' | 'NEXT_HAND';

export class Game {
	id?: string;
	currentPot: number;
	players: Player[];
	communityCards: Card[];
	phase: GamePhase | string;
	currentBet: number;
	currentPlayerIndex?: number;
	playerActions: Record<string, boolean>;
	playersWhoHaveNotActed?: Player[];
	
	
	smallBlind?: number;
	bigBlind?: number;
	dealerPosition?: number;
	
	
	winnerName?: string;
	winningHandDescription?: string;
	winnerIds?: string[];
	
	
	isFinished?: boolean;
	handNumber?: number;
	handLifecycleState?: HandLifecycleState;
	
	
	minRaiseAmount?: number;
	lastRaiseAmount?: number;

	constructor() {
		this.currentPot = 0;
		this.players = [];
		this.communityCards = [];
		this.phase = 'PRE_FLOP';
		this.currentBet = 0;
		this.playerActions = {};
		this.playersWhoHaveNotActed = [];
		this.smallBlind = 10;
		this.bigBlind = 20;
		this.minRaiseAmount = 20;
		this.isFinished = false;
		this.handNumber = 1;
		this.handLifecycleState = 'IN_PROGRESS';
	}

	
	getPhaseDisplayName(): string {
		const names: Record<string, string> = {
			'PRE_FLOP': 'Pre-Flop',
			'FLOP': 'Flop',
			'TURN': 'Turn',
			'RIVER': 'River',
			'SHOWDOWN': 'Showdown'
		};
		return names[this.phase] || this.phase;
	}

	
	hasFinished(): boolean {
		return this.isFinished || this.phase === 'SHOWDOWN';
	}

	
	getActivePlayersCount(): number {
		return this.players.filter(p => !p.folded).length;
	}

	
	getCurrentPlayer(): Player | undefined {
		if (this.currentPlayerIndex !== undefined && this.currentPlayerIndex < this.players.length) {
			return this.players[this.currentPlayerIndex];
		}
		return undefined;
	}

	
	isPlayerTurn(playerId: string): boolean {
		const currentPlayer = this.getCurrentPlayer();
		return currentPlayer?.id === playerId;
	}
}


