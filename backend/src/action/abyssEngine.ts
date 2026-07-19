import { randomUUID } from "node:crypto";

export interface AbyssInput {
  sequence: number; moveX: number; moveY: number; aimX: number; aimY: number; shooting: boolean;
}
export type AbyssWeapon = "SWORD" | "SPEAR" | "BOW" | "HAMMER";
export interface AbyssActor {
  id: string; kind: "PLAYER" | "BOSS"; x: number; y: number; vx: number; vy: number;
  hp: number; maxHp: number; colorHex: string; name: string; weapon: AbyssWeapon;
  kills: number; deaths: number; respawnAt: number; facingX: number; facingY: number; attacking: boolean;
}
interface Runtime extends AbyssActor {
  kind: "PLAYER"; input: AbyssInput; sequence: number; rotation: number; cooldown: number; isBot: boolean;
}
interface Projectile { id: string; ownerId: string; x: number; y: number; vx: number; vy: number; damage: number; ttl: number }
export interface AbyssSnapshot {
  serverTime: number; tick: number; level: 1; maxLevel: 1; bossLevel: true;
  mode: "COOP_RAYCAST_RPG"; remainingMs: number; winnerId: string | null; completed: boolean;
  actors: AbyssActor[]; projectiles: Projectile[]; items: Array<{ id: string; x: number; y: number; type: AbyssWeapon | "HEAL" }>;
  room: {
    seed: number; maze: number[][]; exit: { x: number; y: number };
    obstacles: Array<{ x: number; y: number; width: number; height: number }>;
  };
}

const DURATION = 300_000;
const SIZE = 17;

/** Mundo 2D autoritativo; Android únicamente proyecta rayos sobre `maze`. */
export class AbyssEngine {
  private readonly players = new Map<string, Runtime>();
  private readonly maze: number[][];
  private readonly exit = { x: SIZE - 2.5, y: SIZE - 2.5 };
  private projectiles: Projectile[] = [];
  private boss: AbyssActor;
  private elapsed = 0;
  private tick = 0;
  private completed = false;
  private seed: number;

  constructor(seed: string, players: Array<{ id: string; name: string; colorHex: string; isBot?: boolean }>) {
    this.seed = hash(seed);
    this.maze = this.generateMaze();
    players.slice(0, 4).forEach((source, index) => this.players.set(source.id, {
      ...source, kind: "PLAYER", x: 1.5 + index * .24, y: 1.5, vx: 0, vy: 0,
      hp: 6, maxHp: 6, weapon: "BOW", kills: 0, deaths: 0, respawnAt: 0,
      facingX: 1, facingY: 0, attacking: false, input: neutral(), sequence: -1,
      rotation: 0, cooldown: 0, isBot: source.isBot === true,
    }));
    this.boss = {
      id: "ABYSS_WARDEN", kind: "BOSS", x: SIZE - 3.5, y: SIZE - 3.5, vx: 0, vy: 0,
      hp: 160, maxHp: 160, colorHex: "#FF3D71", name: "Guardián Arcano", weapon: "HAMMER",
      kills: 0, deaths: 0, respawnAt: 0, facingX: -1, facingY: 0, attacking: false,
    };
  }

  applyInput(id: string, input: Partial<AbyssInput>): void {
    const player = this.players.get(id); const sequence = Math.floor(Number(input.sequence));
    if (!player || player.isBot || !Number.isFinite(sequence) || sequence <= player.sequence) return;
    player.sequence = sequence;
    player.input = {
      sequence, moveX: clamp(Number(input.moveX), -1, 1), moveY: clamp(Number(input.moveY), -1, 1),
      aimX: clamp(Number(input.aimX), -1, 1), aimY: clamp(Number(input.aimY), -1, 1), shooting: input.shooting === true,
    };
  }

  update(rawDt: number): void {
    if (this.completed) return;
    const dt = clamp(rawDt, 0, .08); this.elapsed += dt * 1_000; this.tick += 1;
    for (const player of this.players.values()) {
      if (player.isBot) this.botIntent(player);
      player.rotation += player.input.aimX * dt * 2.5;
      const forward = -player.input.moveY;
      const strafe = player.input.moveX;
      const dx = Math.cos(player.rotation) * forward - Math.sin(player.rotation) * strafe;
      const dy = Math.sin(player.rotation) * forward + Math.cos(player.rotation) * strafe;
      player.vx = dx * 2.3; player.vy = dy * 2.3;
      this.move(player, player.vx * dt, player.vy * dt);
      player.facingX = Math.cos(player.rotation); player.facingY = Math.sin(player.rotation);
      player.cooldown -= dt; player.attacking = player.input.shooting;
      if (player.input.shooting && player.cooldown <= 0) this.cast(player);
      if (Math.hypot(player.x - this.exit.x, player.y - this.exit.y) < .55 && this.boss.hp <= 0) this.completed = true;
    }
    this.updateProjectiles(dt);
    if (this.elapsed >= DURATION) this.completed = true;
  }

  snapshot(now = Date.now()): AbyssSnapshot {
    const actors: AbyssActor[] = [...this.players.values()].map(({ input: _i, sequence: _s, rotation: _r, cooldown: _c, isBot: _b, ...actor }) => actor);
    return {
      serverTime: now, tick: this.tick, level: 1, maxLevel: 1, bossLevel: true,
      mode: "COOP_RAYCAST_RPG", remainingMs: Math.max(0, DURATION - this.elapsed),
      winnerId: this.completed && this.boss.hp <= 0 ? "PLAYERS" : null, completed: this.completed,
      actors: [...actors, this.boss], projectiles: this.projectiles, items: [],
      room: {
        seed: this.seed, maze: this.maze, exit: this.exit,
        obstacles: this.maze.flatMap((row, y) => row.map((wall, x) => wall ? { x, y, width: 1, height: 1 } : null)).filter((v): v is { x: number; y: number; width: number; height: number } => v !== null),
      },
    };
  }

  private cast(player: Runtime): void {
    player.cooldown = .32;
    this.projectiles.push({
      id: randomUUID(), ownerId: player.id, x: player.x, y: player.y,
      vx: player.facingX * 7, vy: player.facingY * 7, damage: 2, ttl: 2.2,
    });
  }

  private updateProjectiles(dt: number): void {
    for (const shot of this.projectiles) {
      shot.x += shot.vx * dt; shot.y += shot.vy * dt; shot.ttl -= dt;
      if (this.wall(shot.x, shot.y)) shot.ttl = 0;
      if (shot.ownerId !== this.boss.id && Math.hypot(shot.x - this.boss.x, shot.y - this.boss.y) < .55) {
        this.boss.hp = Math.max(0, this.boss.hp - shot.damage); shot.ttl = 0;
        const owner = this.players.get(shot.ownerId); if (owner) owner.kills += 2;
      }
    }
    this.projectiles = this.projectiles.filter((shot) => shot.ttl > 0);
  }

  private move(actor: AbyssActor, dx: number, dy: number): void {
    if (!this.wall(actor.x + dx, actor.y)) actor.x += dx;
    if (!this.wall(actor.x, actor.y + dy)) actor.y += dy;
  }

  private wall(x: number, y: number): boolean {
    return this.maze[Math.floor(y)]?.[Math.floor(x)] !== 0;
  }

  private botIntent(bot: Runtime): void {
    const dx = this.boss.x - bot.x; const dy = this.boss.y - bot.y;
    const target = Math.atan2(dy, dx); let delta = target - bot.rotation;
    while (delta > Math.PI) delta -= Math.PI * 2; while (delta < -Math.PI) delta += Math.PI * 2;
    bot.input = { sequence: ++bot.sequence, moveX: 0, moveY: Math.abs(delta) < .4 ? -1 : 0, aimX: clamp(delta * 2, -1, 1), aimY: 0, shooting: Math.abs(delta) < .14 };
  }

  private generateMaze(): number[][] {
    const maze = Array.from({ length: SIZE }, (_, y) => Array.from({ length: SIZE }, (_, x) => x === 0 || y === 0 || x === SIZE - 1 || y === SIZE - 1 ? 1 : 0));
    for (let y = 2; y < SIZE - 2; y += 2) for (let x = 2; x < SIZE - 2; x += 2) {
      maze[y]![x] = 1;
      if (this.random() < .5) maze[y]![x + (this.random() < .5 ? 1 : -1)] = 1;
      else maze[y + (this.random() < .5 ? 1 : -1)]![x] = 1;
    }
    // corredor seguro diagonal para garantizar salida.
    for (let index = 1; index < SIZE - 1; index += 1) { maze[1]![index] = 0; maze[index]![SIZE - 2] = 0; }
    for (let x = SIZE - 4; x < SIZE - 1; x += 1) maze[SIZE - 4]![x] = 0;
    return maze;
  }

  private random(): number { this.seed = (this.seed * 1664525 + 1013904223) >>> 0; return this.seed / 0x1_0000_0000; }
}
function neutral(): AbyssInput { return { sequence: 0, moveX: 0, moveY: 0, aimX: 0, aimY: 0, shooting: false }; }
function clamp(value: number, min: number, max: number): number { return Number.isFinite(value) ? Math.max(min, Math.min(max, value)) : 0; }
function hash(value: string): number { let result = 2166136261; for (const char of value) result = Math.imul(result ^ char.charCodeAt(0), 16777619); return result >>> 0; }
