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
export type RoomPhase = "LOBBY" | "PLAYING" | "FINISHED";

export interface RoomConfig {
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
  targetPlayerId: string;
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
  | "SAME_TEAM";

export type PowerResult =
  | {
      accepted: true;
      attackerId: string;
      targetPlayerId: string;
      type: "FOG";
    }
  | {
      accepted: false;
      code: PowerRejectionCode;
      message: string;
    };
