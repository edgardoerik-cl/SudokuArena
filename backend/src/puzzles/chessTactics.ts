export type ChessTeam = "BLUE" | "RED";
export type ChessPieceType = "PAWN" | "KNIGHT" | "ROOK";
export type ChessSkill = "PHALANX" | "EARTHQUAKE" | "PIERCING_RAY";

export interface Piece {
  id: string;
  team: ChessTeam;
  type: ChessPieceType;
  hp: number;
  maxHp: number;
  ap: number;
  maxAp: number;
  defense: number;
  statusEffects: string[];
}

export interface BoardPoint { row: number; col: number }

export function calculateDamage(baseDamage: number, defense: number, multiplier = 1): number {
  return Math.max(1, Math.round(baseDamage * multiplier - defense * .45));
}

export function movementRange(piece: Piece, origin: BoardPoint): BoardPoint[] {
  if (piece.ap <= 0) return [];
  const result: BoardPoint[] = [];
  for (let dy = -piece.ap; dy <= piece.ap; dy += 1) {
    for (let dx = -piece.ap; dx <= piece.ap; dx += 1) {
      const distance = Math.abs(dx) + Math.abs(dy);
      if (distance === 0 || distance > piece.ap) continue;
      const row = origin.row + dy; const col = origin.col + dx;
      if (row >= 0 && row < 8 && col >= 0 && col < 8) result.push({ row, col });
    }
  }
  return result;
}

export function attackRange(piece: Piece, origin: BoardPoint): BoardPoint[] {
  if (piece.ap <= 0) return [];
  if (piece.type === "PAWN") {
    const direction = piece.team === "BLUE" ? 1 : -1;
    return [-1, 0, 1]
      .map((dx) => ({ row: origin.row + direction, col: origin.col + dx }))
      .filter(({ row, col }) => row >= 0 && row < 8 && col >= 0 && col < 8);
  }
  if (piece.type === "KNIGHT") {
    return [[-2, -1], [-2, 1], [-1, -2], [-1, 2], [1, -2], [1, 2], [2, -1], [2, 1]]
      .map(([dy, dx]) => ({ row: origin.row + dy!, col: origin.col + dx! }))
      .filter(({ row, col }) => row >= 0 && row < 8 && col >= 0 && col < 8);
  }
  const result: BoardPoint[] = [];
  for (const [dy, dx] of [[-1, 0], [1, 0], [0, -1], [0, 1]]) {
    for (let distance = 1; distance < 8; distance += 1) {
      const row = origin.row + dy! * distance; const col = origin.col + dx! * distance;
      if (row < 0 || row >= 8 || col < 0 || col >= 8) break;
      result.push({ row, col });
    }
  }
  return result;
}

export function skillCost(skill: ChessSkill): number {
  return skill === "PHALANX" ? 2 : skill === "EARTHQUAKE" ? 3 : 3;
}

export function skillFor(piece: Piece): ChessSkill {
  return piece.type === "PAWN" ? "PHALANX" : piece.type === "KNIGHT" ? "EARTHQUAKE" : "PIERCING_RAY";
}
