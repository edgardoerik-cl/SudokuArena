import { randomUUID } from "node:crypto";
/** Simulación autoritativa de Abismo Arena. Se ejecuta a 20 Hz y el cliente interpola a 60 FPS. */
export class AbyssEngine {
    players = new Map();
    enemies = [];
    projectiles = [];
    items = [];
    obstacles = [];
    tickNumber = 0;
    level = 1;
    completed = false;
    seed;
    constructor(seed, players) {
        this.seed = hash(seed);
        players.forEach((player, index) => {
            this.players.set(player.id, {
                ...player,
                kind: "PLAYER",
                x: .42 + (index % 2) * .16,
                y: .76 + Math.floor(index / 2) * .08,
                vx: 0,
                vy: 0,
                hp: 6,
                maxHp: 6,
                input: neutralInput(),
                lastSequence: -1,
                shotCooldown: 0,
                damage: 1,
                fireRate: .34,
                speed: .34,
            });
        });
        this.generateLevel();
    }
    applyInput(playerId, input) {
        const player = this.players.get(playerId);
        const sequence = Math.floor(Number(input.sequence));
        if (!player || !Number.isFinite(sequence) || sequence <= player.lastSequence)
            return;
        player.lastSequence = sequence;
        player.input = {
            sequence,
            moveX: clamp(Number(input.moveX), -1, 1),
            moveY: clamp(Number(input.moveY), -1, 1),
            aimX: clamp(Number(input.aimX), -1, 1),
            aimY: clamp(Number(input.aimY), -1, 1),
            shooting: input.shooting === true,
        };
    }
    update(dtSeconds) {
        if (this.completed)
            return;
        const dt = clamp(dtSeconds, 0, .08);
        this.tickNumber += 1;
        for (const player of this.players.values())
            this.updatePlayer(player, dt);
        this.updateEnemies(dt);
        this.updateProjectiles(dt);
        this.collectItems();
        if (this.enemies.length === 0) {
            if (this.level >= 20)
                this.completed = true;
            else {
                this.level += 1;
                this.players.forEach((player) => {
                    player.x = .5;
                    player.y = .8;
                    player.hp = Math.min(player.maxHp, player.hp + 1);
                });
                this.generateLevel();
            }
        }
    }
    snapshot(now = Date.now()) {
        return {
            serverTime: now,
            tick: this.tickNumber,
            level: this.level,
            maxLevel: 20,
            bossLevel: this.level % 5 === 0,
            completed: this.completed,
            actors: [
                ...[...this.players.values()].map(({ input: _input, lastSequence: _sequence, shotCooldown: _cooldown, damage: _damage, fireRate: _fireRate, speed: _speed, ...actor }) => actor),
                ...this.enemies,
            ],
            projectiles: this.projectiles,
            items: this.items,
            room: { seed: this.seed + this.level, obstacles: this.obstacles },
        };
    }
    updatePlayer(player, dt) {
        const length = Math.hypot(player.input.moveX, player.input.moveY) || 1;
        player.vx = player.input.moveX / length * player.speed;
        player.vy = player.input.moveY / length * player.speed;
        this.moveActor(player, dt);
        player.shotCooldown -= dt;
        const aimLength = Math.hypot(player.input.aimX, player.input.aimY);
        if (player.input.shooting && aimLength > .15 && player.shotCooldown <= 0) {
            player.shotCooldown = player.fireRate;
            this.projectiles.push({
                id: randomUUID(), ownerId: player.id, x: player.x, y: player.y,
                vx: player.input.aimX / aimLength * .72, vy: player.input.aimY / aimLength * .72,
                damage: player.damage, ttl: 1.8,
            });
        }
    }
    updateEnemies(dt) {
        const livingPlayers = [...this.players.values()].filter((player) => player.hp > 0);
        for (const enemy of this.enemies) {
            const target = livingPlayers.sort((a, b) => distance(enemy, a) - distance(enemy, b))[0];
            if (!target)
                continue;
            const dx = target.x - enemy.x;
            const dy = target.y - enemy.y;
            const length = Math.hypot(dx, dy) || 1;
            const speed = enemy.kind === "BOSS" ? .15 : .11 + (this.level * .002);
            enemy.vx = dx / length * speed;
            enemy.vy = dy / length * speed;
            this.moveActor(enemy, dt);
            if (distance(enemy, target) < .045)
                target.hp = Math.max(0, target.hp - dt * (enemy.kind === "BOSS" ? 1.3 : .55));
        }
    }
    updateProjectiles(dt) {
        for (const shot of this.projectiles) {
            shot.x += shot.vx * dt;
            shot.y += shot.vy * dt;
            shot.ttl -= dt;
            const hit = this.enemies.find((enemy) => distance(shot, enemy) < (enemy.kind === "BOSS" ? .075 : .038));
            if (hit) {
                hit.hp -= shot.damage;
                shot.ttl = 0;
                if (hit.hp <= 0 && this.random() < .24) {
                    this.items.push({ id: randomUUID(), x: hit.x, y: hit.y, type: ["DAMAGE", "FIRE_RATE", "HEAL"][Math.floor(this.random() * 3)] });
                }
            }
        }
        this.enemies = this.enemies.filter((enemy) => enemy.hp > 0);
        this.projectiles = this.projectiles.filter((shot) => shot.ttl > 0 && shot.x > 0 && shot.x < 1 && shot.y > 0 && shot.y < 1);
    }
    collectItems() {
        this.items = this.items.filter((item) => {
            const player = [...this.players.values()].find((target) => distance(item, target) < .045);
            if (!player)
                return true;
            if (item.type === "DAMAGE")
                player.damage += .35;
            if (item.type === "FIRE_RATE")
                player.fireRate = Math.max(.11, player.fireRate - .035);
            if (item.type === "HEAL")
                player.hp = Math.min(player.maxHp, player.hp + 2);
            return false;
        });
    }
    generateLevel() {
        this.seed = (this.seed * 1664525 + 1013904223) >>> 0;
        const boss = this.level % 5 === 0;
        const count = boss ? 1 : Math.min(4 + this.level, 16);
        this.enemies = Array.from({ length: count }, (_, index) => {
            const hp = boss ? 25 + this.level * 3 : 2 + Math.floor(this.level / 4);
            return {
                id: `enemy-${this.level}-${index}`,
                kind: boss ? "BOSS" : "ENEMY",
                x: .12 + this.random() * .76,
                y: .10 + this.random() * .44,
                vx: 0, vy: 0, hp, maxHp: hp,
            };
        });
        this.obstacles = Array.from({ length: Math.min(2 + Math.floor(this.level / 3), 7) }, () => ({
            x: .15 + this.random() * .65,
            y: .22 + this.random() * .45,
            width: .06 + this.random() * .11,
            height: .05 + this.random() * .09,
        }));
    }
    moveActor(actor, dt) {
        const nextX = clamp(actor.x + actor.vx * dt, .035, .965);
        const nextY = clamp(actor.y + actor.vy * dt, .04, .96);
        if (!this.obstacles.some((wall) => inside(nextX, actor.y, wall)))
            actor.x = nextX;
        if (!this.obstacles.some((wall) => inside(actor.x, nextY, wall)))
            actor.y = nextY;
    }
    random() {
        this.seed = (this.seed * 1664525 + 1013904223) >>> 0;
        return this.seed / 0x1_0000_0000;
    }
}
function neutralInput() {
    return { sequence: 0, moveX: 0, moveY: 0, aimX: 0, aimY: -1, shooting: false };
}
function clamp(value, min, max) {
    return Number.isFinite(value) ? Math.max(min, Math.min(max, value)) : 0;
}
function inside(x, y, wall) {
    return x > wall.x && x < wall.x + wall.width && y > wall.y && y < wall.y + wall.height;
}
function distance(a, b) {
    return Math.hypot(a.x - b.x, a.y - b.y);
}
function hash(value) {
    let result = 2166136261;
    for (const char of value)
        result = Math.imul(result ^ char.charCodeAt(0), 16777619);
    return result >>> 0;
}
//# sourceMappingURL=abyssEngine.js.map