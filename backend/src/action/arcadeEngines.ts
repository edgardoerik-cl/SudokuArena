import type { PlayerState } from "../types.js";

type TetrominoName = "I" | "J" | "L" | "O" | "S" | "T" | "Z";
export type TetrisAction = "LEFT" | "RIGHT" | "ROTATE" | "SOFT_DROP" | "HARD_DROP" | "HOLD" | "CLEAN_BOMB";
export type PacmanDirection = "UP" | "RIGHT" | "DOWN" | "LEFT" | "STOP";
export interface DemolitionBrick {
  id: string;
  x: number;
  y: number;
  width: number;
  height: number;
  hp: number;
  color: number;
}

const TETROMINOES: Record<TetrominoName, number[][][]> = {
  I: [[[0, 0], [0, 1], [0, 2], [0, 3]], [[0, 2], [1, 2], [2, 2], [3, 2]]],
  J: [[[0, 0], [1, 0], [1, 1], [1, 2]], [[0, 1], [0, 2], [1, 1], [2, 1]], [[1, 0], [1, 1], [1, 2], [2, 2]], [[0, 1], [1, 1], [2, 0], [2, 1]]],
  L: [[[0, 2], [1, 0], [1, 1], [1, 2]], [[0, 1], [1, 1], [2, 1], [2, 2]], [[1, 0], [1, 1], [1, 2], [2, 0]], [[0, 0], [0, 1], [1, 1], [2, 1]]],
  O: [[[0, 1], [0, 2], [1, 1], [1, 2]]],
  S: [[[0, 1], [0, 2], [1, 0], [1, 1]], [[0, 1], [1, 1], [1, 2], [2, 2]]],
  T: [[[0, 1], [1, 0], [1, 1], [1, 2]], [[0, 1], [1, 1], [1, 2], [2, 1]], [[1, 0], [1, 1], [1, 2], [2, 1]], [[0, 1], [1, 0], [1, 1], [2, 1]]],
  Z: [[[0, 0], [0, 1], [1, 1], [1, 2]], [[0, 2], [1, 1], [1, 2], [2, 1]]],
};
const PIECE_NAMES = Object.keys(TETROMINOES) as TetrominoName[];

interface FallingPiece { type: TetrominoName; rotation: number; row: number; col: number }
interface TetrisPlayer {
  id: string; name: string; colorHex: string; board: number[][]; piece: FallingPiece;
  bag: TetrominoName[]; next: TetrominoName; score: number; lines: number; gameOver: boolean;
  lockDeadline: number; impact: number; hold: TetrominoName | null; canHold: boolean; cleanBombUsed: boolean;
  abilityEnergy: number; bombsUsed: number; garbageSent: number;
}

export class TetrisArenaEngine {
  private readonly players = new Map<string, TetrisPlayer>();
  private tickNumber = 0;
  private lastGravityAt = Date.now();
  private completed = false;

  syncPlayers(players: PlayerState[]): void {
    for (const player of players) if (!this.players.has(player.id)) {
      const bag = shuffledBag();
      const first = bag.pop()!;
      const next = bag.pop()!;
      this.players.set(player.id, {
        id: player.id, name: player.name, colorHex: player.color,
        board: grid(20, 10, 0), piece: { type: first, rotation: 0, row: -1, col: 3 },
        bag, next, score: 0, lines: 0, gameOver: false, lockDeadline: 0, impact: 0,
        hold: null, canHold: true, cleanBombUsed: false,
        abilityEnergy: 0, bombsUsed: 0, garbageSent: 0,
      });
    }
  }

  input(playerId: string, action: TetrisAction): boolean {
    const player = this.players.get(playerId);
    if (!player || player.gameOver || this.completed) return false;
    if (action === "HOLD") {
      if (!player.canHold || player.abilityEnergy < 1) return false;
      const current = player.piece.type;
      if (player.hold === null) {
        player.piece = { type: player.next, rotation: 0, row: -1, col: 3 };
        if (!player.bag.length) player.bag = shuffledBag();
        player.next = player.bag.pop()!;
      } else {
        player.piece = { type: player.hold, rotation: 0, row: -1, col: 3 };
      }
      player.hold = current;
      player.canHold = false;
      player.abilityEnergy -= 1;
      player.lockDeadline = 0;
      if (this.collides(player)) player.gameOver = true;
      return true;
    }
    if (action === "CLEAN_BOMB") {
      if (player.abilityEnergy < 4) return false;
      player.board.splice(17, 3);
      player.board.unshift(...grid(3, 10, 0));
      player.cleanBombUsed = true;
      player.bombsUsed += 1;
      player.abilityEnergy -= 4;
      player.score += 150;
      return true;
    }
    if (action === "LEFT") return this.tryMove(player, 0, -1);
    if (action === "RIGHT") return this.tryMove(player, 0, 1);
    if (action === "SOFT_DROP") {
      if (this.tryMove(player, 1, 0)) return true;
      if (!player.lockDeadline) player.lockDeadline = Date.now() + 600;
      return true;
    }
    if (action === "ROTATE") {
      const previous = player.piece.rotation;
      player.piece.rotation = (previous + 1) % TETROMINOES[player.piece.type].length;
      for (const [dy, dx] of [[0, 0], [0, -1], [0, 1], [-1, 0], [1, 0]]) {
        player.piece.row += dy!; player.piece.col += dx!;
        if (!this.collides(player)) { player.lockDeadline = 0; return true; }
        player.piece.row -= dy!; player.piece.col -= dx!;
      }
      player.piece.rotation = previous;
      return false;
    }
    while (this.tryMove(player, 1, 0)) player.score += 2;
    player.impact += 1;
    this.lock(player, true);
    return true;
  }

  tick(now = Date.now()): void {
    const fastestLevel = Math.max(0, ...[...this.players.values()].map((player) => Math.floor(player.lines / 10)));
    const gravityMs = Math.max(110, 520 - fastestLevel * 35);
    if (this.completed || now - this.lastGravityAt < gravityMs) return;
    this.lastGravityAt = now; this.tickNumber += 1;
    for (const player of this.players.values()) {
      if (player.gameOver) continue;
      if (this.tryMove(player, 1, 0)) player.lockDeadline = 0;
      else if (!player.lockDeadline) player.lockDeadline = now + 600;
      else if (now >= player.lockDeadline) this.lock(player);
    }
    const alive = [...this.players.values()].filter((player) => !player.gameOver);
    this.completed = this.players.size > 1 && alive.length <= 1;
  }

  snapshot(): any {
    return {
      serverTime: Date.now(), tick: this.tickNumber, completed: this.completed,
      players: [...this.players.values()].map((player) => ({
        ...player,
        board: this.boardWithPiece(player),
        bag: undefined,
      })),
    };
  }

  private cells(piece: FallingPiece): Array<[number, number]> {
    return TETROMINOES[piece.type][piece.rotation]!.map(([row, col]) => [piece.row + row!, piece.col + col!]);
  }
  private collides(player: TetrisPlayer): boolean {
    return this.cells(player.piece).some(([row, col]) => col < 0 || col >= 10 || row >= 20 || (row >= 0 && player.board[row]![col] !== 0));
  }
  private tryMove(player: TetrisPlayer, dy: number, dx: number): boolean {
    player.piece.row += dy; player.piece.col += dx;
    if (!this.collides(player)) return true;
    player.piece.row -= dy; player.piece.col -= dx;
    return false;
  }
  private lock(player: TetrisPlayer, kineticImpact = false): void {
    const color = PIECE_NAMES.indexOf(player.piece.type) + 1;
    for (const [row, col] of this.cells(player.piece)) {
      if (row < 0) { player.gameOver = true; continue; }
      player.board[row]![col] = color;
    }
    const remaining = player.board.filter((row) => row.some((value) => value === 0));
    const cleared = 20 - remaining.length;
    while (remaining.length < 20) remaining.unshift(Array(10).fill(0));
    player.board = remaining;
    if (kineticImpact && Math.random() < .10) this.settleLooseBlocks(player.board);
    player.lines += cleared;
    player.abilityEnergy = Math.min(8, player.abilityEnergy + cleared);
    player.score += [0, 100, 300, 500, 800][cleared] ?? 0;
    // Cada línea completada genera una línea de presión en cada rival. La
    // misma acción también carga las habilidades del jugador.
    if (cleared > 0 && this.players.size > 1) for (const rival of this.players.values()) {
      if (rival.id !== player.id && !rival.gameOver) {
        this.addGarbage(rival, cleared);
        player.garbageSent += cleared;
      }
    }
    if (!player.bag.length) player.bag = shuffledBag();
    player.piece = { type: player.next, rotation: 0, row: -1, col: 3 };
    player.canHold = true;
    player.lockDeadline = 0;
    player.next = player.bag.pop()!;
    if (this.collides(player)) player.gameOver = true;
  }
  private settleLooseBlocks(board: number[][]): void {
    // Una sola pasada conservadora: cada bloque suelto cae verticalmente al
    // hueco más bajo de su columna sin atravesar otros bloques.
    for (let col = 0; col < 10; col += 1) {
      const values = board.map((row) => row[col]!).filter((value) => value !== 0);
      for (let row = 0; row < 20; row += 1) board[row]![col] = row < 20 - values.length ? 0 : values[row - (20 - values.length)]!;
    }
  }
  private addGarbage(player: TetrisPlayer, lines: number): void {
    for (let count = 0; count < lines; count += 1) {
      player.board.shift();
      const hole = Math.floor(Math.random() * 10);
      player.board.push(Array.from({ length: 10 }, (_, col) => col === hole ? 0 : 8));
    }
  }
  private boardWithPiece(player: TetrisPlayer): number[][] {
    const output = player.board.map((row) => [...row]);
    const color = PIECE_NAMES.indexOf(player.piece.type) + 1;
    this.cells(player.piece).forEach(([row, col]) => { if (row >= 0 && row < 20 && col >= 0 && col < 10) output[row]![col] = color; });
    return output;
  }
}

const PACMAN_MAP = [
  "000000000000000",
  "021111011111120",
  "010001010100010",
  "011111111111110",
  "010101000101010",
  "011101111101110",
  "000101000101000",
  "111111111111111",
  "000101000101000",
  "011101111101110",
  "010101000101010",
  "011111111111110",
  "010001010100010",
  "021111011111120",
  "000000000000000",
].map((row) => [...row].map(Number));

interface PacActor {
  id: string;
  x: number;
  y: number;
  direction: PacmanDirection;
  queuedDirection?: PacmanDirection;
  lives?: number;
  score?: number;
  colorHex?: string;
  name?: string;
}
interface Ghost extends PacActor {
  mode: "CHASE" | "SCATTER" | "FRIGHTENED" | "EATEN";
  homeX: number;
  homeY: number;
  eatenUntil?: number;
}

export class PacmanArenaEngine {
  private readonly players = new Map<string, PacActor>();
  private readonly pills = new Set<string>();
  private readonly powerPills = new Set<string>();
  private readonly ghosts: Ghost[] = [
    { id: "blinky", x: 7, y: 7, direction: "LEFT", mode: "CHASE", homeX: 13, homeY: 1 },
    { id: "pinky", x: 6, y: 7, direction: "RIGHT", mode: "CHASE", homeX: 1, homeY: 1 },
    { id: "inky", x: 8, y: 7, direction: "UP", mode: "CHASE", homeX: 13, homeY: 13 },
    { id: "clyde", x: 7, y: 8, direction: "DOWN", mode: "CHASE", homeX: 1, homeY: 13 },
  ];
  private frightenedUntil = 0;
  private tickNumber = 0;
  private completed = false;
  private started = false;

  constructor() {
    PACMAN_MAP.forEach((row, y) => row.forEach((tile, x) => {
      if (tile === 1) this.pills.add(`${x}:${y}`);
      if (tile === 2) this.powerPills.add(`${x}:${y}`);
    }));
  }
  syncPlayers(players: PlayerState[]): void {
    players.forEach((player, index) => {
      if (!this.players.has(player.id)) this.players.set(player.id, {
        id: player.id, name: player.name, colorHex: player.color,
        x: 7 + (index % 2), y: 11 + Math.floor(index / 2), direction: "STOP", lives: 3, score: 0,
      });
    });
  }
  input(playerId: string, direction: PacmanDirection): boolean {
    const player = this.players.get(playerId);
    if (!player || !["UP", "RIGHT", "DOWN", "LEFT", "STOP"].includes(direction)) return false;
    // Buffer de giro: conserva la dirección solicitada hasta alcanzar una
    // intersección donde sea válida, sin frenar el movimiento actual.
    player.queuedDirection = direction;
    if (direction !== "STOP") this.started = true;
    return true;
  }
  tick(now = Date.now()): void {
    if (this.completed || !this.started) return;
    this.tickNumber += 1;
    for (const player of this.players.values()) {
      if (player.queuedDirection && this.canMove(player, player.queuedDirection)) {
        player.direction = player.queuedDirection;
        delete player.queuedDirection;
      }
      this.moveActor(player, player.direction);
      const key = `${player.x}:${player.y}`;
      if (this.pills.delete(key)) player.score = (player.score ?? 0) + 10;
      if (this.powerPills.delete(key)) {
        player.score = (player.score ?? 0) + 50;
        this.frightenedUntil = now + 7_000;
      }
    }
    this.ghosts.forEach((ghost, index) => {
      if ((ghost.eatenUntil ?? 0) > now) {
        ghost.mode = "EATEN";
        ghost.x = 7; ghost.y = 7; ghost.direction = "STOP";
        return;
      }
      ghost.mode = now < this.frightenedUntil
          ? "FRIGHTENED"
          : Math.floor(now / 8_000) % 2 === 0 ? "CHASE" : "SCATTER";
      // Tres movimientos de fantasma por cada cuatro de Pac-Man: 75%.
      if (this.tickNumber % 4 === 0) return;
      const target = ghost.mode === "SCATTER"
          ? { x: ghost.homeX, y: ghost.homeY }
          : this.ghostTarget(ghost, index);
      ghost.direction = this.bestGhostDirection(ghost, target, ghost.mode === "FRIGHTENED", index);
      this.moveActor(ghost, ghost.direction);
    });
    for (const player of this.players.values()) for (const ghost of this.ghosts) {
      if (player.x !== ghost.x || player.y !== ghost.y) continue;
      if (ghost.mode === "FRIGHTENED") {
        player.score = (player.score ?? 0) + 200;
        ghost.mode = "EATEN";
        ghost.x = 7; ghost.y = 7; ghost.direction = "STOP";
        ghost.eatenUntil = now + 10_000;
      } else {
        if (ghost.mode === "EATEN") continue;
        player.lives = Math.max(0, (player.lives ?? 0) - 1);
        player.x = 7; player.y = 11; player.direction = "STOP"; delete player.queuedDirection;
      }
    }
    this.completed = this.pills.size === 0 || [...this.players.values()].every((player) => (player.lives ?? 0) <= 0);
  }
  snapshot(): any {
    return {
      serverTime: Date.now(), tick: this.tickNumber, completed: this.completed, tilemap: PACMAN_MAP,
      status: this.started ? "PLAYING" : "WAITING",
      pills: [...this.pills], powerPills: [...this.powerPills],
      players: [...this.players.values()], ghosts: this.ghosts,
    };
  }
  private moveActor(actor: PacActor, direction: PacmanDirection): void {
    const [dy, dx] = direction === "UP" ? [-1, 0] : direction === "RIGHT" ? [0, 1]
      : direction === "DOWN" ? [1, 0] : direction === "LEFT" ? [0, -1] : [0, 0];
    let x = actor.x + dx; const y = actor.y + dy;
    // Túnel horizontal clásico en la fila central.
    if (actor.y === 7 && x < 0) x = PACMAN_MAP[0]!.length - 1;
    if (actor.y === 7 && x >= PACMAN_MAP[0]!.length) x = 0;
    if (PACMAN_MAP[y]?.[x] && PACMAN_MAP[y]![x] !== 0) { actor.x = x; actor.y = y; }
  }
  private canMove(actor: PacActor, direction: PacmanDirection): boolean {
    const [dy, dx] = direction === "UP" ? [-1, 0] : direction === "RIGHT" ? [0, 1]
      : direction === "DOWN" ? [1, 0] : direction === "LEFT" ? [0, -1] : [0, 0];
    let x = actor.x + dx; const y = actor.y + dy;
    if (actor.y === 7 && (x < 0 || x >= PACMAN_MAP[0]!.length)) return true;
    return Boolean(PACMAN_MAP[y]?.[x] && PACMAN_MAP[y]![x] !== 0);
  }
  private closestPlayer(actor: PacActor): PacActor | null {
    return [...this.players.values()].filter((player) => (player.lives ?? 0) > 0)
      .sort((a, b) => manhattan(actor, a) - manhattan(actor, b))[0] ?? null;
  }
  private ghostTarget(ghost: Ghost, index: number): { x: number; y: number } {
    const player = this.closestPlayer(ghost);
    if (!player) return { x: 7, y: 7 };
    const vector = player.direction === "UP" ? { x: 0, y: -1 }
      : player.direction === "RIGHT" ? { x: 1, y: 0 }
        : player.direction === "DOWN" ? { x: 0, y: 1 }
          : player.direction === "LEFT" ? { x: -1, y: 0 }
            : { x: 0, y: 0 };
    if (index === 0) return { x: player.x, y: player.y }; // Blinky: directo.
    if (index === 1) return { x: player.x + vector.x * 4, y: player.y + vector.y * 4 }; // Pinky: adelantado.
    if (index === 2) { // Inky: flanquea usando a Blinky como vector.
      const blinky = this.ghosts[0]!;
      const pivot = { x: player.x + vector.x * 2, y: player.y + vector.y * 2 };
      return { x: pivot.x * 2 - blinky.x, y: pivot.y * 2 - blinky.y };
    }
    return manhattan(ghost, player) >= 8 ? { x: player.x, y: player.y } : { x: ghost.homeX, y: ghost.homeY };
  }
  private bestGhostDirection(ghost: Ghost, target: { x: number; y: number }, flee: boolean, salt: number): PacmanDirection {
    const options: PacmanDirection[] = ["UP", "RIGHT", "DOWN", "LEFT"];
    const valid = options.map((direction) => {
      const [dy, dx] = direction === "UP" ? [-1, 0] : direction === "RIGHT" ? [0, 1] : direction === "DOWN" ? [1, 0] : [0, -1];
      return { direction, x: ghost.x + dx, y: ghost.y + dy };
    }).filter(({ x, y }) => PACMAN_MAP[y]?.[x] && PACMAN_MAP[y]![x] !== 0);
    valid.sort((a, b) => {
      const first = Math.abs(a.x - target.x) + Math.abs(a.y - target.y);
      const second = Math.abs(b.x - target.x) + Math.abs(b.y - target.y);
      return (flee ? second - first : first - second) || ((a.x + a.y + salt) % 3 - (b.x + b.y + salt) % 3);
    });
    return valid[0]?.direction ?? "STOP";
  }
}

interface DemolitionPlayer {
  id: string;
  name: string;
  colorHex: string;
  paddleX: number;
  ballX: number;
  ballY: number;
  velocityX: number;
  velocityY: number;
  lives: number;
  score: number;
  level: number;
  bricks: DemolitionBrick[];
  completed: boolean;
  isBot: boolean;
  balls: Array<{ id: string; x: number; y: number; vx: number; vy: number }>;
  drops: Array<{ id: string; x: number; y: number; type: "MULTIBALL" | "LASER" | "SPEED" }>;
  laserUntil: number;
  speedUntil: number;
}

export class DemolitionArenaEngine {
  private readonly players = new Map<string, DemolitionPlayer>();
  private tickNumber = 0;

  syncPlayers(players: PlayerState[]): void {
    players.forEach((player) => {
      if (this.players.has(player.id)) return;
      this.players.set(player.id, {
        id: player.id,
        name: player.name,
        colorHex: player.color,
        paddleX: .5,
        ballX: .5,
        ballY: .78,
        velocityX: .34,
        velocityY: -.48,
        lives: 3,
        score: 0,
        level: 1,
        bricks: this.generateLevel(1),
        completed: false,
        isBot: player.isBot,
        balls: [{ id: "main", x: .5, y: .78, vx: .34, vy: -.48 }],
        drops: [],
        laserUntil: 0,
        speedUntil: 0,
      });
    });
  }

  input(playerId: string, paddleX: number): boolean {
    const player = this.players.get(playerId);
    if (!player || !Number.isFinite(paddleX)) return false;
    player.paddleX = Math.max(.11, Math.min(.89, paddleX));
    return true;
  }

  tick(deltaSeconds = 1 / 60): void {
    this.tickNumber += 1;
    for (const player of this.players.values()) {
      if (player.completed || player.lives <= 0) continue;
      if (player.isBot) {
        const maxStep = deltaSeconds * .52;
        player.paddleX += Math.max(-maxStep, Math.min(maxStep, player.ballX - player.paddleX));
        player.paddleX = Math.max(.11, Math.min(.89, player.paddleX));
      }
      this.integrate(player, Math.min(.033, Math.max(.001, deltaSeconds)));
    }
  }

  snapshot(): any {
    return {
      serverTime: Date.now(),
      tick: this.tickNumber,
      completed: [...this.players.values()].length > 0
        && [...this.players.values()].every((player) => player.lives <= 0 || player.completed),
      players: [...this.players.values()].map((player) => ({
        ...player,
        bricks: player.bricks.map((brick) => ({ ...brick })),
      })),
    };
  }

  private integrate(player: DemolitionPlayer, deltaSeconds: number): void {
    const now = Date.now();
    player.drops.forEach((drop) => { drop.y += deltaSeconds * .22; });
    const caught = player.drops.filter((drop) => drop.y >= .88 && drop.y <= .97 && Math.abs(drop.x - player.paddleX) <= .13);
    caught.forEach((drop) => this.applyDemolitionDrop(player, drop.type, now));
    player.drops = player.drops.filter((drop) => drop.y <= 1 && !caught.includes(drop));
    if (player.laserUntil > now && this.tickNumber % 12 === 0) {
      const target = player.bricks.filter((brick) => Math.abs(brick.x + brick.width / 2 - player.paddleX) <= .07)
        .sort((a, b) => b.y - a.y)[0];
      if (target) {
        target.hp -= 1;
        if (target.hp <= 0) player.bricks = player.bricks.filter((brick) => brick.id !== target.id);
        player.score += 8 * player.level;
      }
    }
    player.balls.forEach((ball) => this.integrateDemolitionBall(player, ball, deltaSeconds, now));
    player.balls = player.balls.filter((ball) => ball.y <= 1.05);
    if (player.balls.length === 0) {
      player.lives -= 1;
      this.resetBall(player);
      return;
    }
    const primary = player.balls[0]!;
    player.ballX = primary.x; player.ballY = primary.y;
    player.velocityX = primary.vx; player.velocityY = primary.vy;
    if (player.bricks.length === 0) {
      player.level += 1;
      player.score += 250;
      player.bricks = this.generateLevel(player.level);
      player.drops = [];
      this.resetBall(player);
    }
  }

  private integrateDemolitionBall(
    player: DemolitionPlayer,
    ball: { id: string; x: number; y: number; vx: number; vy: number },
    deltaSeconds: number,
    now: number,
  ): void {
    const radius = .014;
    let nextX = ball.x + ball.vx * deltaSeconds;
    let nextY = ball.y + ball.vy * deltaSeconds;
    if (nextX - radius <= 0 || nextX + radius >= 1) {
      ball.vx *= -1;
      nextX = Math.max(radius, Math.min(1 - radius, nextX));
    }
    if (nextY - radius <= 0) {
      ball.vy = Math.abs(ball.vy);
      nextY = radius;
    }
    const paddle = { x: player.paddleX - .105, y: .91, width: .21, height: .025 };
    if (ball.vy > 0 && circleIntersectsRect(nextX, nextY, radius, paddle)) {
      const relative = (nextX - player.paddleX) / (paddle.width / 2);
      const speed = Math.min(.92, Math.hypot(ball.vx, ball.vy) * 1.015);
      ball.vx = relative * speed * .78;
      ball.vy = -Math.sqrt(Math.max(.08, speed * speed - ball.vx * ball.vx));
      nextY = paddle.y - radius;
    }
    const hit = player.bricks.find((brick) =>
      brick.hp > 0 && circleIntersectsRect(nextX, nextY, radius, brick)
    );
    if (hit) {
      const previousXInside = ball.x >= hit.x && ball.x <= hit.x + hit.width;
      if (previousXInside) ball.vy *= -1;
      else ball.vx *= -1;
      hit.hp -= 1;
      player.score += 10 * player.level * (player.speedUntil > now ? 2 : 1);
      if (hit.hp <= 0) {
        player.bricks = player.bricks.filter((brick) => brick.id !== hit.id);
        if (Math.random() < .24) {
          const types = ["MULTIBALL", "LASER", "SPEED"] as const;
          player.drops.push({
            id: `drop-${this.tickNumber}-${hit.id}`,
            x: hit.x + hit.width / 2,
            y: hit.y + hit.height,
            type: types[Math.floor(Math.random() * types.length)]!,
          });
        }
      }
    }
    ball.x = nextX;
    ball.y = nextY;
  }

  private resetBall(player: DemolitionPlayer): void {
    player.ballX = player.paddleX;
    player.ballY = .82;
    const speed = Math.min(.72, .48 + player.level * .025);
    player.velocityX = (Math.random() < .5 ? -1 : 1) * speed * .55;
    player.velocityY = -speed;
    player.balls = [{
      id: `ball-${this.tickNumber}`,
      x: player.ballX, y: player.ballY, vx: player.velocityX, vy: player.velocityY,
    }];
  }

  private applyDemolitionDrop(player: DemolitionPlayer, type: "MULTIBALL" | "LASER" | "SPEED", now: number): void {
    if (type === "MULTIBALL") {
      const source = player.balls[0];
      if (source) {
        player.balls.push(
          { ...source, id: `multi-a-${this.tickNumber}`, vx: -Math.abs(source.vx || .32) },
          { ...source, id: `multi-b-${this.tickNumber}`, vx: Math.abs(source.vx || .32) },
        );
      }
    } else if (type === "LASER") player.laserUntil = now + 5_000;
    else {
      player.speedUntil = now + 7_000;
      player.balls.forEach((ball) => { ball.vx *= 1.22; ball.vy *= 1.22; });
    }
  }

  private generateLevel(level: number): DemolitionBrick[] {
    const rows = Math.min(8, 4 + Math.floor((level - 1) / 2));
    const columns = 8;
    const gap = .012;
    const width = (1 - .12 - gap * (columns - 1)) / columns;
    const height = .045;
    const bricks: DemolitionBrick[] = [];
    for (let row = 0; row < rows; row += 1) {
      for (let col = 0; col < columns; col += 1) {
        // Los huecos cambian por nivel para crear mapas progresivos distintos.
        if ((row * 3 + col + level) % Math.max(5, 9 - level) === 0) continue;
        bricks.push({
          id: `L${level}-${row}-${col}`,
          x: .06 + col * (width + gap),
          y: .08 + row * (height + gap),
          width,
          height,
          hp: level >= 4 && (row + col + level) % 4 === 0 ? 2 : 1,
          color: (row + level) % 6,
        });
      }
    }
    return bricks;
  }
}

function shuffledBag(): TetrominoName[] {
  const bag = [...PIECE_NAMES];
  for (let index = bag.length - 1; index > 0; index -= 1) {
    const target = Math.floor(Math.random() * (index + 1));
    [bag[index], bag[target]] = [bag[target]!, bag[index]!];
  }
  return bag;
}
function grid(rows: number, columns: number, value: number): number[][] {
  return Array.from({ length: rows }, () => Array(columns).fill(value));
}
function manhattan(a: { x: number; y: number }, b: { x: number; y: number }): number {
  return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
}

function circleIntersectsRect(
  circleX: number,
  circleY: number,
  radius: number,
  rect: { x: number; y: number; width: number; height: number },
): boolean {
  const closestX = Math.max(rect.x, Math.min(circleX, rect.x + rect.width));
  const closestY = Math.max(rect.y, Math.min(circleY, rect.y + rect.height));
  const dx = circleX - closestX;
  const dy = circleY - closestY;
  return dx * dx + dy * dy <= radius * radius;
}
