import { randomUUID } from "node:crypto";
const MATCH_DURATION_MS = 180_000;
const KILL_TARGET = 10;
const RESPAWN_MS = 2_500;
const SPAWNS = [
    { x: .08, y: .10 },
    { x: .92, y: .10 },
    { x: .08, y: .90 },
    { x: .92, y: .90 },
];
/**
 * Arena PvP autoritativa a 20 Hz.
 *
 * El servidor controla movimiento, laberinto, ataques, armas, daño, bajas y
 * reaparición. El cliente únicamente envía intención de movimiento/apuntado.
 */
export class AbyssEngine {
    players = new Map();
    projectiles = [];
    items = [];
    obstacles = [];
    tickNumber = 0;
    elapsedMs = 0;
    nextWeaponAt = 2_500;
    completed = false;
    winnerId = null;
    seed;
    constructor(seed, players) {
        this.seed = hash(seed);
        this.obstacles = this.generateMaze();
        players.slice(0, 4).forEach((player, index) => {
            const spawn = SPAWNS[index] ?? SPAWNS[0];
            this.players.set(player.id, {
                ...player,
                kind: "PLAYER",
                x: spawn.x,
                y: spawn.y,
                vx: 0,
                vy: 0,
                hp: 6,
                maxHp: 6,
                weapon: "SWORD",
                kills: 0,
                deaths: 0,
                respawnAt: 0,
                facingX: index % 2 === 0 ? 1 : -1,
                facingY: 0,
                attacking: false,
                input: neutralInput(),
                lastSequence: -1,
                attackCooldown: 0,
                invulnerableUntil: 1_200,
                weaponUntil: 0,
                isBot: player.isBot === true,
            });
        });
    }
    applyInput(playerId, input) {
        const player = this.players.get(playerId);
        const sequence = Math.floor(Number(input.sequence));
        if (!player || player.isBot || !Number.isFinite(sequence) || sequence <= player.lastSequence)
            return;
        player.lastSequence = sequence;
        player.input = normalizeInput(input, sequence);
    }
    update(dtSeconds) {
        if (this.completed)
            return;
        const dt = clamp(dtSeconds, 0, .08);
        this.elapsedMs += dt * 1_000;
        this.tickNumber += 1;
        for (const player of this.players.values()) {
            this.updateRespawn(player);
            if (player.hp <= 0)
                continue;
            if (player.isBot)
                this.updateBotIntent(player);
            this.updatePlayer(player, dt);
        }
        this.updateProjectiles(dt);
        this.collectItems();
        this.spawnWeapons();
        const leader = [...this.players.values()].sort((a, b) => b.kills - a.kills || a.deaths - b.deaths)[0];
        if (leader && (leader.kills >= KILL_TARGET || this.elapsedMs >= MATCH_DURATION_MS)) {
            this.completed = true;
            this.winnerId = leader.id;
        }
    }
    snapshot(now = Date.now()) {
        return {
            serverTime: now,
            tick: this.tickNumber,
            level: 1,
            maxLevel: 1,
            bossLevel: false,
            mode: "PVP_FFA",
            remainingMs: Math.max(0, MATCH_DURATION_MS - this.elapsedMs),
            winnerId: this.winnerId,
            completed: this.completed,
            actors: [...this.players.values()].map((player) => ({
                id: player.id,
                kind: "PLAYER",
                x: player.x,
                y: player.y,
                vx: player.vx,
                vy: player.vy,
                hp: player.hp,
                maxHp: player.maxHp,
                colorHex: player.colorHex,
                name: player.name,
                weapon: player.weapon,
                kills: player.kills,
                deaths: player.deaths,
                respawnAt: player.respawnAt > this.elapsedMs
                    ? now + (player.respawnAt - this.elapsedMs)
                    : 0,
                facingX: player.facingX,
                facingY: player.facingY,
                attacking: player.attacking,
            })),
            projectiles: this.projectiles,
            items: this.items,
            room: { seed: this.seed, obstacles: this.obstacles },
        };
    }
    updatePlayer(player, dt) {
        const moveLength = Math.hypot(player.input.moveX, player.input.moveY);
        if (moveLength > .08) {
            player.vx = player.input.moveX / moveLength * .31;
            player.vy = player.input.moveY / moveLength * .31;
            this.moveActor(player, dt);
        }
        else {
            player.vx = 0;
            player.vy = 0;
        }
        const aimLength = Math.hypot(player.input.aimX, player.input.aimY);
        if (aimLength > .15) {
            player.facingX = player.input.aimX / aimLength;
            player.facingY = player.input.aimY / aimLength;
        }
        player.attackCooldown -= dt;
        player.attacking = player.input.shooting && player.attackCooldown > -.10;
        if (player.input.shooting && player.attackCooldown <= 0)
            this.attack(player);
        if (player.weapon !== "SWORD" && this.elapsedMs >= player.weaponUntil)
            player.weapon = "SWORD";
    }
    attack(attacker) {
        const stats = weaponStats(attacker.weapon);
        attacker.attackCooldown = stats.cooldown;
        attacker.attacking = true;
        if (attacker.weapon === "BOW") {
            this.projectiles.push({
                id: randomUUID(),
                ownerId: attacker.id,
                x: attacker.x,
                y: attacker.y,
                vx: attacker.facingX * .72,
                vy: attacker.facingY * .72,
                damage: stats.damage,
                ttl: 1.5,
            });
            return;
        }
        const targets = [...this.players.values()]
            .filter((target) => target.id !== attacker.id && target.hp > 0 && this.elapsedMs >= target.invulnerableUntil)
            .map((target) => ({
            target,
            distance: distance(attacker, target),
            facing: facingDot(attacker, target),
        }))
            .filter(({ distance: range, facing }) => range <= stats.range && facing > .16)
            .sort((a, b) => a.distance - b.distance);
        const limit = attacker.weapon === "HAMMER" ? 2 : 1;
        targets.slice(0, limit).forEach(({ target }) => this.damagePlayer(target, attacker, stats.damage));
    }
    updateProjectiles(dt) {
        for (const shot of this.projectiles) {
            shot.x += shot.vx * dt;
            shot.y += shot.vy * dt;
            shot.ttl -= dt;
            if (this.obstacles.some((wall) => inside(shot.x, shot.y, wall))) {
                shot.ttl = 0;
                continue;
            }
            const owner = this.players.get(shot.ownerId);
            const victim = [...this.players.values()].find((target) => target.id !== shot.ownerId &&
                target.hp > 0 &&
                this.elapsedMs >= target.invulnerableUntil &&
                distance(shot, target) < .038);
            if (owner && victim) {
                this.damagePlayer(victim, owner, shot.damage);
                shot.ttl = 0;
            }
        }
        this.projectiles = this.projectiles.filter((shot) => shot.ttl > 0 && shot.x > 0 && shot.x < 1 && shot.y > 0 && shot.y < 1);
    }
    damagePlayer(victim, attacker, damage) {
        victim.hp = Math.max(0, victim.hp - damage);
        if (victim.hp > 0)
            return;
        attacker.kills += 1;
        victim.deaths += 1;
        victim.respawnAt = this.elapsedMs + RESPAWN_MS;
        victim.input = neutralInput();
    }
    updateRespawn(player) {
        if (player.hp > 0 || player.respawnAt <= 0 || this.elapsedMs < player.respawnAt)
            return;
        const spawn = SPAWNS[(player.deaths + [...this.players.keys()].indexOf(player.id)) % SPAWNS.length] ?? SPAWNS[0];
        player.x = spawn.x;
        player.y = spawn.y;
        player.hp = player.maxHp;
        player.weapon = "SWORD";
        player.weaponUntil = 0;
        player.respawnAt = 0;
        player.invulnerableUntil = this.elapsedMs + 1_200;
    }
    updateBotIntent(bot) {
        const target = [...this.players.values()]
            .filter((player) => player.id !== bot.id && player.hp > 0)
            .sort((a, b) => distance(bot, a) - distance(bot, b))[0];
        if (!target) {
            bot.input = neutralInput();
            return;
        }
        const dx = target.x - bot.x;
        const dy = target.y - bot.y;
        const length = Math.hypot(dx, dy) || 1;
        const desiredRange = bot.weapon === "BOW" ? .28 : weaponStats(bot.weapon).range * .72;
        bot.input = {
            sequence: bot.lastSequence + 1,
            moveX: distance(bot, target) > desiredRange ? dx / length : 0,
            moveY: distance(bot, target) > desiredRange ? dy / length : 0,
            aimX: dx / length,
            aimY: dy / length,
            shooting: distance(bot, target) <= (bot.weapon === "BOW" ? .58 : weaponStats(bot.weapon).range),
        };
        bot.lastSequence += 1;
    }
    collectItems() {
        this.items = this.items.filter((item) => {
            const player = [...this.players.values()].find((target) => target.hp > 0 && distance(item, target) < .05);
            if (!player)
                return true;
            if (item.type === "HEAL")
                player.hp = Math.min(player.maxHp, player.hp + 2);
            else {
                player.weapon = item.type;
                player.weaponUntil = this.elapsedMs + 14_000;
            }
            return false;
        });
    }
    spawnWeapons() {
        if (this.elapsedMs < this.nextWeaponAt || this.items.length >= 5)
            return;
        this.nextWeaponAt = this.elapsedMs + 5_000 + this.random() * 4_000;
        const types = ["SPEAR", "BOW", "HAMMER", "HEAL"];
        for (let attempt = 0; attempt < 20; attempt += 1) {
            const item = {
                id: randomUUID(),
                x: .10 + this.random() * .80,
                y: .10 + this.random() * .80,
                type: types[Math.floor(this.random() * types.length)] ?? "SPEAR",
            };
            if (!this.obstacles.some((wall) => inside(item.x, item.y, wall))) {
                this.items.push(item);
                return;
            }
        }
    }
    generateMaze() {
        const jitter = () => (this.random() - .5) * .035;
        return [
            { x: .20 + jitter(), y: .18, width: .035, height: .28 },
            { x: .20 + jitter(), y: .61, width: .035, height: .21 },
            { x: .76 + jitter(), y: .18, width: .035, height: .21 },
            { x: .76 + jitter(), y: .54, width: .035, height: .28 },
            { x: .34, y: .31 + jitter(), width: .16, height: .035 },
            { x: .50, y: .67 + jitter(), width: .16, height: .035 },
            { x: .41 + jitter(), y: .43, width: .035, height: .18 },
            { x: .58 + jitter(), y: .39, width: .035, height: .18 },
            { x: .28, y: .82 + jitter(), width: .18, height: .035 },
            { x: .54, y: .14 + jitter(), width: .18, height: .035 },
        ];
    }
    moveActor(actor, dt) {
        const nextX = clamp(actor.x + actor.vx * dt, .035, .965);
        const nextY = clamp(actor.y + actor.vy * dt, .04, .96);
        if (!this.obstacles.some((wall) => inside(nextX, actor.y, wall, .018)))
            actor.x = nextX;
        if (!this.obstacles.some((wall) => inside(actor.x, nextY, wall, .018)))
            actor.y = nextY;
    }
    random() {
        this.seed = (this.seed * 1664525 + 1013904223) >>> 0;
        return this.seed / 0x1_0000_0000;
    }
}
function weaponStats(weapon) {
    switch (weapon) {
        case "SPEAR": return { damage: 1.05, range: .135, cooldown: .52 };
        case "BOW": return { damage: .85, range: .58, cooldown: .42 };
        case "HAMMER": return { damage: 1.8, range: .095, cooldown: .78 };
        default: return { damage: 1.15, range: .082, cooldown: .36 };
    }
}
function normalizeInput(input, sequence) {
    return {
        sequence,
        moveX: clamp(Number(input.moveX), -1, 1),
        moveY: clamp(Number(input.moveY), -1, 1),
        aimX: clamp(Number(input.aimX), -1, 1),
        aimY: clamp(Number(input.aimY), -1, 1),
        shooting: input.shooting === true,
    };
}
function neutralInput() {
    return { sequence: 0, moveX: 0, moveY: 0, aimX: 0, aimY: -1, shooting: false };
}
function clamp(value, min, max) {
    return Number.isFinite(value) ? Math.max(min, Math.min(max, value)) : 0;
}
function inside(x, y, wall, padding = 0) {
    return x > wall.x - padding && x < wall.x + wall.width + padding &&
        y > wall.y - padding && y < wall.y + wall.height + padding;
}
function distance(a, b) {
    return Math.hypot(a.x - b.x, a.y - b.y);
}
function facingDot(attacker, target) {
    const dx = target.x - attacker.x;
    const dy = target.y - attacker.y;
    const length = Math.hypot(dx, dy) || 1;
    return attacker.facingX * dx / length + attacker.facingY * dy / length;
}
function hash(value) {
    let result = 2166136261;
    for (const char of value)
        result = Math.imul(result ^ char.charCodeAt(0), 16777619);
    return result >>> 0;
}
//# sourceMappingURL=abyssEngine.js.map