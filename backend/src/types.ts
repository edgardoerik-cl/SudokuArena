export interface PublicCell {
  value: number | null;
  ownerId: string | null;
  clearing: boolean;
  golden: boolean;
}

export interface PlayerState {
  id: string;
  name: string;
  slot: number;
  color: string;
  score: number;
  blockedUntil: number;
  energy: number;
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
  | "INVALID_TARGET";

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
