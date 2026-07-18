import { randomUUID } from "node:crypto";

export interface AbyssInput {
  sequence: number;
  moveX: number;
  moveY: number;
  aimX: number;
  aimY: number;
  shooting: boolean;
}

export type AbyssWeapon = "SWORD" | "SPEAR" | "BOW" | "HAMMER";

export interface AbyssActor {
  id: string;
  kind: "PLAYER" | "BOSS";
  x: number;
  y: number;
  vx: number;
  vy: number;
  hp: number;
  maxHp: number;
  colorHex: string;
  name: string;
  weapon: AbyssWeapon;
  kills: number;
  deaths: number;
  respawnAt: number;
  facingX: number;
  facingY: number;
  attacking: boolean;
}

interface Projectile {
  id: string;
  ownerId: string;
  x: number;
  y: number;
  vx: number;
  vy: number;
  damage: number;
  ttl: number;
}

interface PlayerRuntime extends AbyssActor {
  kind: "PLAYER";
  input: AbyssInput;
  lastSequence: number;
  attackCooldown: number;
  jumpWasHeld: boolean;
  grounded: boolean;
  invulnerableUntil: number;
  isBot: boolean;
}

export interface AbyssSnapshot {
  serverTime: number;
  tick: number;
  level: 1;
  maxLevel: 1;
  bossLevel: true;
  mode: "COOP_SIDE_SCROLLER";
  remainingMs: number;
  winnerId: string | null;
  completed: boolean;
  actors: AbyssActor[];
  projectiles: Projectile[];
  items: Array<{ id: string; x: number; y: number; type: AbyssWeapon | "HEAL" }>;
  room: {
    seed: number;
    obstacles: Array<{ x: number; y: number; width: number; height: number }>;
  };
}

const MATCH_DURATION_MS = 240_000;
const GRAVITY = 1.15;
const JUMP_SPEED = -.53;
const PLAYER_RADIUS = .027;
const RESPAWN_MS = 2_500;
const SPAWNS = [.09, .18, .27, .36];

/**
 * Side-scroller cooperativo autoritativo a 20 Hz.
 * `moveY < -0.45` se interpreta como salto para conservar compatibilidad con
 * los clientes anteriores que ya enviaban un joystick bidimensional.
 */
export class AbyssEngine {
  private readonly players = new Map<string, PlayerRuntime>();
  private projectiles: Projectile[] = [];
  private items: AbyssSnapshot["items"] = [];
  private readonly platforms: AbyssSnapshot["room"]["obstacles"];
  private boss: AbyssActor;
  private tickNumber = 0;
  private elapsedMs = 0;
  private nextBossShotAt = 1_200;
  private completed = false;
  private seed: number;

  constructor(
    seed: string,
    players: Array<{ id: string; name: string; colorHex: string; isBot?: boolean }>,
  ) {
    this.seed = hash(seed);
    this.platforms = this.generatePlatforms();
    players.slice(0, 4).forEach((player, index) => {
      this.players.set(player.id, {
        ...player,
        kind: "PLAYER",
        x: SPAWNS[index] ?? .09,
        y: .88,
        vx: 0,
        vy: 0,
        hp: 6,
        maxHp: 6,
        weapon: index % 2 === 0 ? "BOW" : "SPEAR",
        kills: 0,
        deaths: 0,
        respawnAt: 0,
        facingX: 1,
        facingY: 0,
        attacking: false,
        input: neutralInput(),
        lastSequence: -1,
        attackCooldown: 0,
        jumpWasHeld: false,
        grounded: true,
        invulnerableUntil: 1_000,
        isBot: player.isBot === true,
      });
    });
    this.boss = {
      id: "ABYSS_BOSS",
      kind: "BOSS",
      x: .86,
      y: .84,
      vx: 0,
      vy: 0,
      hp: 120,
      maxHp: 120,
      colorHex: "#FF3D71",
      name: "Guardián del Abismo",
      weapon: "HAMMER",
      kills: 0,
      deaths: 0,
      respawnAt: 0,
      facingX: -1,
      facingY: 0,
      attacking: false,
    };
  }

  applyInput(playerId: string, input: Partial<AbyssInput>): void {
    const player = this.players.get(playerId);
    const sequence = Math.floor(Number(input.sequence));
    if (!player || player.isBot || !Number.isFinite(sequence) || sequence <= player.lastSequence) return;
    player.lastSequence = sequence;
    player.input = normalizeInput(input, sequence);
  }

  update(dtSeconds: number): void {
    if (this.completed) return;
    const dt = clamp(dtSeconds, 0, .08);
    this.elapsedMs += dt * 1_000;
    this.tickNumber += 1;

    for (const player of this.players.values()) {
      this.updateRespawn(player);
      if (player.hp <= 0) continue;
      if (player.isBot) this.updateBotIntent(player);
      this.updatePlayer(player, dt);
    }
    this.updateBoss(dt);
    this.updateProjectiles(dt);
    this.collectItems();

    if (this.boss.hp <= 0 || this.elapsedMs >= MATCH_DURATION_MS) {
      this.completed = true;
    }
  }

  snapshot(now = Date.now()): AbyssSnapshot {
    const players = [...this.players.values()].map(({ input: _input, lastSequence: _last, attackCooldown: _cooldown,
      jumpWasHeld: _jump, grounded: _grounded, invulnerableUntil: _invulnerable, isBot: _bot, ...actor }) => ({
      ...actor,
      respawnAt: actor.respawnAt > this.elapsedMs ? now + actor.respawnAt - this.elapsedMs : 0,
    }));
    return {
      serverTime: now,
      tick: this.tickNumber,
      level: 1,
      maxLevel: 1,
      bossLevel: true,
      mode: "COOP_SIDE_SCROLLER",
      remainingMs: Math.max(0, MATCH_DURATION_MS - this.elapsedMs),
      winnerId: this.boss.hp <= 0 ? "PLAYERS" : null,
      completed: this.completed,
      actors: [...players, { ...this.boss }],
      projectiles: this.projectiles,
      items: this.items,
      room: { seed: this.seed, obstacles: this.platforms },
    };
  }

  private updatePlayer(player: PlayerRuntime, dt: number): void {
    player.vx = player.input.moveX * .34;
    if (Math.abs(player.input.moveX) > .08) player.facingX = Math.sign(player.input.moveX);

    const jumpHeld = player.input.moveY < -.45;
    if (jumpHeld && !player.jumpWasHeld && player.grounded) {
      player.vy = JUMP_SPEED;
      player.grounded = false;
    }
    player.jumpWasHeld = jumpHeld;
    player.vy += GRAVITY * dt;
    this.moveWithPlatforms(player, dt);

    player.attackCooldown -= dt;
    player.attacking = player.input.shooting;
    if (player.input.shooting && player.attackCooldown <= 0) this.fireAtBoss(player);
  }

  private fireAtBoss(player: PlayerRuntime): void {
    player.attackCooldown = player.weapon === "BOW" ? .30 : .42;
    const dx = this.boss.x - player.x;
    const dy = this.boss.y - player.y;
    const length = Math.hypot(dx, dy) || 1;
    player.facingX = Math.sign(dx) || player.facingX;
    this.projectiles.push({
      id: randomUUID(),
      ownerId: player.id,
      x: player.x,
      y: player.y - .025,
      vx: dx / length * .74,
      vy: dy / length * .74,
      damage: player.weapon === "HAMMER" ? 2.2 : player.weapon === "SPEAR" ? 1.5 : 1,
      ttl: 1.8,
    });
  }

  private updateBoss(dt: number): void {
    if (this.boss.hp <= 0) return;
    this.boss.x = .82 + Math.sin(this.elapsedMs / 1_500) * .08;
    this.boss.attacking = this.elapsedMs >= this.nextBossShotAt - 220;
    if (this.elapsedMs < this.nextBossShotAt) return;
    this.nextBossShotAt = this.elapsedMs + 1_050;
    const living = [...this.players.values()].filter((player) => player.hp > 0);
    const target = living[Math.floor(this.random() * living.length)];
    if (!target) return;
    const dx = target.x - this.boss.x;
    const dy = target.y - this.boss.y;
    const length = Math.hypot(dx, dy) || 1;
    this.projectiles.push({
      id: randomUUID(),
      ownerId: this.boss.id,
      x: this.boss.x,
      y: this.boss.y - .08,
      vx: dx / length * .50,
      vy: dy / length * .50,
      damage: 1,
      ttl: 2.4,
    });
  }

  private updateProjectiles(dt: number): void {
    for (const shot of this.projectiles) {
      shot.x += shot.vx * dt;
      shot.y += shot.vy * dt;
      shot.ttl -= dt;
      if (shot.ownerId === this.boss.id) {
        const victim = [...this.players.values()].find((player) =>
          player.hp > 0 && this.elapsedMs >= player.invulnerableUntil &&
          Math.hypot(player.x - shot.x, player.y - shot.y) < .045
        );
        if (victim) {
          victim.hp = Math.max(0, victim.hp - shot.damage);
          shot.ttl = 0;
          if (victim.hp <= 0) {
            victim.deaths += 1;
            victim.respawnAt = this.elapsedMs + RESPAWN_MS;
          }
        }
      } else if (this.boss.hp > 0 && Math.hypot(this.boss.x - shot.x, this.boss.y - shot.y) < .075) {
        this.boss.hp = Math.max(0, this.boss.hp - shot.damage);
        const owner = this.players.get(shot.ownerId);
        if (owner) owner.kills += Math.round(shot.damage);
        shot.ttl = 0;
      }
    }
    this.projectiles = this.projectiles.filter((shot) =>
      shot.ttl > 0 && shot.x > -.05 && shot.x < 1.05 && shot.y > -.1 && shot.y < 1.1
    );
  }

  private moveWithPlatforms(player: PlayerRuntime, dt: number): void {
    player.x = clamp(player.x + player.vx * dt, PLAYER_RADIUS, 1 - PLAYER_RADIUS);
    const previousFeet = player.y + PLAYER_RADIUS;
    const nextY = player.y + player.vy * dt;
    const nextFeet = nextY + PLAYER_RADIUS;
    let landedY: number | null = null;
    if (player.vy >= 0) {
      for (const platform of this.platforms) {
        const insideX = player.x >= platform.x - PLAYER_RADIUS && player.x <= platform.x + platform.width + PLAYER_RADIUS;
        if (insideX && previousFeet <= platform.y + .008 && nextFeet >= platform.y) {
          if (landedY == null || platform.y < landedY) landedY = platform.y;
        }
      }
    }
    if (landedY != null) {
      player.y = landedY - PLAYER_RADIUS;
      player.vy = 0;
      player.grounded = true;
    } else {
      player.y = nextY;
      player.grounded = false;
    }
    if (player.y > 1.08) {
      player.hp = 0;
      player.deaths += 1;
      player.respawnAt = this.elapsedMs + RESPAWN_MS;
    }
  }

  private updateRespawn(player: PlayerRuntime): void {
    if (player.hp > 0 || player.respawnAt <= 0 || this.elapsedMs < player.respawnAt) return;
    player.x = SPAWNS[[...this.players.keys()].indexOf(player.id)] ?? .09;
    player.y = .88;
    player.vx = 0;
    player.vy = 0;
    player.hp = player.maxHp;
    player.respawnAt = 0;
    player.grounded = true;
    player.invulnerableUntil = this.elapsedMs + 1_200;
  }

  private updateBotIntent(bot: PlayerRuntime): void {
    const dx = this.boss.x - bot.x;
    const shouldJump = bot.grounded && (this.random() < .015 || (bot.x > .42 && bot.x < .56));
    bot.input = {
      sequence: ++bot.lastSequence,
      moveX: Math.abs(dx) > .28 ? Math.sign(dx) : Math.sin(this.elapsedMs / 900 + bot.x * 10),
      moveY: shouldJump ? -1 : 0,
      aimX: Math.sign(dx),
      aimY: 0,
      shooting: Math.abs(dx) < .78,
    };
  }

  private collectItems(): void {
    // Reservado para mejoras cooperativas futuras; se mantiene en el protocolo.
  }

  private generatePlatforms(): AbyssSnapshot["room"]["obstacles"] {
    return [
      { x: 0, y: .92, width: 1, height: .08 },
      { x: .12, y: .73, width: .21, height: .035 },
      { x: .41, y: .63, width: .18, height: .035 },
      { x: .67, y: .74, width: .20, height: .035 },
      { x: .25, y: .46, width: .19, height: .035 },
      { x: .58, y: .39, width: .18, height: .035 },
    ];
  }

  private random(): number {
    this.seed = (this.seed * 1664525 + 1013904223) >>> 0;
    return this.seed / 0x1_0000_0000;
  }
}

function normalizeInput(input: Partial<AbyssInput>, sequence: number): AbyssInput {
  return {
    sequence,
    moveX: clamp(Number(input.moveX), -1, 1),
    moveY: clamp(Number(input.moveY), -1, 1),
    aimX: clamp(Number(input.aimX), -1, 1),
    aimY: clamp(Number(input.aimY), -1, 1),
    shooting: input.shooting === true,
  };
}

function neutralInput(): AbyssInput {
  return { sequence: 0, moveX: 0, moveY: 0, aimX: 1, aimY: 0, shooting: false };
}

function clamp(value: number, min: number, max: number): number {
  return Number.isFinite(value) ? Math.max(min, Math.min(max, value)) : 0;
}

function hash(value: string): number {
  let result = 2166136261;
  for (const char of value) result = Math.imul(result ^ char.charCodeAt(0), 16777619);
  return result >>> 0;
}
