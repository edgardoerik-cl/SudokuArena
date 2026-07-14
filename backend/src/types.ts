import type { GameType } from "./puzzles/types.js";

export interface PublicCell {
  value: number | null;
  ownerId: string | null;
  clearing: boolean;
  golden: boolean;
  ownerTeamId: string | null;
}

export type TeamMode = "FFA" | "TWO_V_TWO" | "THREE_V_ONE";
export type TileType = "NUMBERS" | "COLORS";
export type BotDifficulty = "EASY" | "MEDIUM" | "HARD";
export type PlayerRole = "PLAYER" | "TEAMMATE" | "BOSS" | "RAIDER";
export type RoomPhase = "LOBBY" | "PLAYING" | "SUDDEN_DEATH" | "FINISHED";
export type BotPersona = "CALCULATOR" | "TRICKSTER" | "GUARDIAN";
export type ActivePower = "FOG" | "REFLECT" | "REVEAL";

export interface RoomConfig {
  gameType: GameType;
  powersEnabled: boolean;
  teamMode: TeamMode;
  tileType: TileType;
  botDifficulty: BotDifficulty;
}

export interface RoomState {
  roomCode: string;
  hostPlayerId: string;
  config: RoomConfig;
  phase: RoomPhase;
  startedAt: number | null;
  endsAt: number | null;
  suddenDeath: boolean;
  rematchVotes: number;
}

export interface PlayerState {
  id: string;
  name: string;
  slot: number;
  color: string;
  score: number;
  blockedUntil: number;
  energy: number;
  teamId: string;
  role: PlayerRole;
  teamScore: number;
  isBot: boolean;
  shieldUntil: number;
  combo: number;
  maxCombo: number;
  comboMultiplier: number;
  botPersona: BotPersona | null;
  powerLoadout: ActivePower[];
}

export type BoardEventType = "MIRROR_HOUR" | "GOLDEN_CELLS";

export interface ActiveBoardEvent {
  type: BoardEventType;
  startedAt: number;
  endsAt: number;
}

export interface GameState {
  gameId: string;
  revision: number;
  serverTime: number;
  board: PublicCell[][];
  players: PlayerState[];
  boardEvent: ActiveBoardEvent | null;
}

export interface PlaceProposal {
  requestId: string;
  row: number;
  column: number;
  value: number;
  clientRevision?: number;
}

export interface PowerProposal {
  type?: "FOG" | "REFLECT" | "REVEAL";
  targetPlayerId?: string;
  row?: number;
  column?: number;
  requestId?: string;
}

export type ReactionEmoji = "LAUGH" | "CRY" | "ANGRY" | "SURPRISED";

export interface ReactionProposal {
  emojiId: ReactionEmoji;
}

export interface MatchResultEntry {
  rank: number;
  playerId: string;
  name: string;
  score: number;
  teamId: string;
  teamScore: number;
  role: PlayerRole;
  isBot: boolean;
  maxCombo: number;
}

export type SectionKind = "row" | "column" | "box";

export interface ConqueredSection {
  kind: SectionKind;
  index: number;
}

export type RejectionCode =
  | "INVALID_PAYLOAD"
  | "PLAYER_NOT_FOUND"
  | "BLOCKED"
  | "CELL_OCCUPIED"
  | "CELL_CLEARING"
  | "INCORRECT_VALUE"
  | "DUPLICATE_REQUEST";

export interface ClearPlan {
  token: string;
  coordinates: Array<{ row: number; column: number }>;
  sectionKeys: string[];
}

export type PlaceResult =
  | {
      accepted: true;
      requestId: string;
      revision: number;
      sections: ConqueredSection[];
      bonus: number;
      cellPoints: number;
      goldenBonus: number;
      combo: number;
      comboMultiplier: number;
      comboBonus: number;
      clearPlan: ClearPlan | null;
    }
  | {
      accepted: false;
      requestId: string;
      code: RejectionCode;
      message: string;
      stateChanged: boolean;
      blockedUntil?: number;
    };

export type PowerRejectionCode =
  | "PLAYER_NOT_FOUND"
  | "TARGET_NOT_FOUND"
  | "SELF_TARGET"
  | "NOT_ENOUGH_ENERGY"
  | "INVALID_TARGET"
  | "POWER_DISABLED"
  | "SAME_TEAM"
  | "INVALID_CELL"
  | "CELL_UNAVAILABLE"
  | "PLAYER_BLOCKED"
  | "POWER_NOT_EQUIPPED";

export type PowerResult =
  | {
      accepted: true;
      attackerId: string;
      targetPlayerId: string;
      recipientPlayerId: string;
      reflected: boolean;
      type: "FOG";
    }
  | {
      accepted: true;
      playerId: string;
      shieldUntil: number;
      type: "REFLECT";
    }
  | {
      accepted: true;
      playerId: string;
      placement: Extract<PlaceResult, { accepted: true }>;
      type: "REVEAL";
    }
  | {
      accepted: false;
      code: PowerRejectionCode;
      message: string;
    };
