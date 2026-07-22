import type { PlayerState } from "../types.js";

export const GAME_TYPES = [
  "SUDOKU",
  "MINESWEEPER",
  "WORD_SEARCH",
  "CROSSWORD",
  "TIC_TAC_TOE",
  "DOTS_AND_BOXES",
  "KAKURO",
  "MATHDOKU",
  "HITORI",
  "CHESS_TACTICS",
  "NURIKABE",
  "BRIDGES",
  "TETRIS_ARENA",
  "HANGMAN",
  "ARROWS_ESCAPE",
  "PACMAN_ARENA",
  "CROSS_LETTERS",
  "SECRET_CODE",
  "CAPITAL_ARENA",
  "NEXUS_ZERO",
  "CHECKERS",
  "DEMOLITION_ARCADE",
  "MEMORY_NEON",
  "MERGE_2048",
  "TOWER_DEFENSE",
  "REACTOR_CHAIN"
] as const;

export type GameType = (typeof GAME_TYPES)[number];
export type PuzzleDifficulty = "EASY" | "MEDIUM" | "HARD" | "EXPERT";
export type CellValue = string | number | boolean | null;
export type CellMeta = Record<string, string | number | boolean | null | string[] | number[]>;

export interface GenericCell {
  value: CellValue;
  isRevealed: boolean;
  ownerId: string | null;
  isBlocked: boolean;
  meta: CellMeta;
}

export interface GenericMove {
  requestId: string;
  row: number;
  col: number;
  val: unknown;
}

export interface GenericBoardState {
  gameId: string;
  gameType: GameType;
  revision: number;
  serverTime: number;
  rows: number;
  columns: number;
  board: GenericCell[][];
  players: PlayerState[];
  completed: boolean;
  meta: Record<string, unknown>;
}

export interface GenericMoveResult {
  accepted: boolean;
  requestId: string;
  code?: "INVALID_MOVE" | "PLAYER_BLOCKED" | "CELL_LOCKED" | "INCORRECT" | "DUPLICATE" | "FINISHED";
  message: string;
  points: number;
  penaltyMs: number;
  completed: boolean;
}

export interface PuzzleBlueprint {
  board: GenericCell[][];
  answers: CellValue[][];
  meta: Record<string, unknown>;
}

export interface PuzzleGenerationOptions {
  seed?: string;
  difficulty?: PuzzleDifficulty;
  level?: number;
}
