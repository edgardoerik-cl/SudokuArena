export interface PublicCell {
  value: number | null;
  ownerId: string | null;
  clearing: boolean;
}

export interface PlayerState {
  id: string;
  name: string;
  slot: number;
  color: string;
  score: number;
  blockedUntil: number;
}

export interface GameState {
  gameId: string;
  revision: number;
  serverTime: number;
  board: PublicCell[][];
  players: PlayerState[];
}

export interface PlaceProposal {
  requestId: string;
  row: number;
  column: number;
  value: number;
  clientRevision?: number;
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
