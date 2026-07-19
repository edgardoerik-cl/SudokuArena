const TETROMINOES = {
    I: [[[0, 0], [0, 1], [0, 2], [0, 3]], [[0, 2], [1, 2], [2, 2], [3, 2]]],
    J: [[[0, 0], [1, 0], [1, 1], [1, 2]], [[0, 1], [0, 2], [1, 1], [2, 1]], [[1, 0], [1, 1], [1, 2], [2, 2]], [[0, 1], [1, 1], [2, 0], [2, 1]]],
    L: [[[0, 2], [1, 0], [1, 1], [1, 2]], [[0, 1], [1, 1], [2, 1], [2, 2]], [[1, 0], [1, 1], [1, 2], [2, 0]], [[0, 0], [0, 1], [1, 1], [2, 1]]],
    O: [[[0, 1], [0, 2], [1, 1], [1, 2]]],
    S: [[[0, 1], [0, 2], [1, 0], [1, 1]], [[0, 1], [1, 1], [1, 2], [2, 2]]],
    T: [[[0, 1], [1, 0], [1, 1], [1, 2]], [[0, 1], [1, 1], [1, 2], [2, 1]], [[1, 0], [1, 1], [1, 2], [2, 1]], [[0, 1], [1, 0], [1, 1], [2, 1]]],
    Z: [[[0, 0], [0, 1], [1, 1], [1, 2]], [[0, 2], [1, 1], [1, 2], [2, 1]]],
};
const PIECE_NAMES = Object.keys(TETROMINOES);
export class TetrisArenaEngine {
    players = new Map();
    tickNumber = 0;
    lastGravityAt = Date.now();
    completed = false;
    syncPlayers(players) {
        for (const player of players)
            if (!this.players.has(player.id)) {
                const bag = shuffledBag();
                const first = bag.pop();
                const next = bag.pop();
                this.players.set(player.id, {
                    id: player.id, name: player.name, colorHex: player.color,
                    board: grid(20, 10, 0), piece: { type: first, rotation: 0, row: -1, col: 3 },
                    bag, next, score: 0, lines: 0, gameOver: false,
                });
            }
    }
    input(playerId, action) {
        const player = this.players.get(playerId);
        if (!player || player.gameOver || this.completed)
            return false;
        if (action === "LEFT")
            return this.tryMove(player, 0, -1);
        if (action === "RIGHT")
            return this.tryMove(player, 0, 1);
        if (action === "SOFT_DROP")
            return this.tryMove(player, 1, 0) || (this.lock(player), true);
        if (action === "ROTATE") {
            const previous = player.piece.rotation;
            player.piece.rotation = (previous + 1) % TETROMINOES[player.piece.type].length;
            if (this.collides(player))
                player.piece.rotation = previous;
            return player.piece.rotation !== previous;
        }
        while (this.tryMove(player, 1, 0))
            player.score += 2;
        this.lock(player);
        return true;
    }
    tick(now = Date.now()) {
        if (this.completed || now - this.lastGravityAt < 520)
            return;
        this.lastGravityAt = now;
        this.tickNumber += 1;
        for (const player of this.players.values()) {
            if (!player.gameOver && !this.tryMove(player, 1, 0))
                this.lock(player);
        }
        const alive = [...this.players.values()].filter((player) => !player.gameOver);
        this.completed = this.players.size > 1 && alive.length <= 1;
    }
    snapshot() {
        return {
            serverTime: Date.now(), tick: this.tickNumber, completed: this.completed,
            players: [...this.players.values()].map((player) => ({
                ...player,
                board: this.boardWithPiece(player),
                bag: undefined,
            })),
        };
    }
    cells(piece) {
        return TETROMINOES[piece.type][piece.rotation].map(([row, col]) => [piece.row + row, piece.col + col]);
    }
    collides(player) {
        return this.cells(player.piece).some(([row, col]) => col < 0 || col >= 10 || row >= 20 || (row >= 0 && player.board[row][col] !== 0));
    }
    tryMove(player, dy, dx) {
        player.piece.row += dy;
        player.piece.col += dx;
        if (!this.collides(player))
            return true;
        player.piece.row -= dy;
        player.piece.col -= dx;
        return false;
    }
    lock(player) {
        const color = PIECE_NAMES.indexOf(player.piece.type) + 1;
        for (const [row, col] of this.cells(player.piece)) {
            if (row < 0) {
                player.gameOver = true;
                continue;
            }
            player.board[row][col] = color;
        }
        const remaining = player.board.filter((row) => row.some((value) => value === 0));
        const cleared = 20 - remaining.length;
        while (remaining.length < 20)
            remaining.unshift(Array(10).fill(0));
        player.board = remaining;
        player.lines += cleared;
        player.score += [0, 100, 300, 500, 800][cleared] ?? 0;
        if (cleared >= 2)
            for (const rival of this.players.values()) {
                if (rival.id !== player.id && !rival.gameOver)
                    this.addGarbage(rival, cleared - 1);
            }
        if (!player.bag.length)
            player.bag = shuffledBag();
        player.piece = { type: player.next, rotation: 0, row: -1, col: 3 };
        player.next = player.bag.pop();
        if (this.collides(player))
            player.gameOver = true;
    }
    addGarbage(player, lines) {
        for (let count = 0; count < lines; count += 1) {
            player.board.shift();
            const hole = Math.floor(Math.random() * 10);
            player.board.push(Array.from({ length: 10 }, (_, col) => col === hole ? 0 : 8));
        }
    }
    boardWithPiece(player) {
        const output = player.board.map((row) => [...row]);
        const color = PIECE_NAMES.indexOf(player.piece.type) + 1;
        this.cells(player.piece).forEach(([row, col]) => { if (row >= 0 && row < 20 && col >= 0 && col < 10)
            output[row][col] = color; });
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
export class PacmanArenaEngine {
    players = new Map();
    pills = new Set();
    powerPills = new Set();
    ghosts = [
        { id: "blinky", x: 7, y: 7, direction: "LEFT", mode: "CHASE", homeX: 13, homeY: 1 },
        { id: "pinky", x: 6, y: 7, direction: "RIGHT", mode: "CHASE", homeX: 1, homeY: 1 },
        { id: "inky", x: 8, y: 7, direction: "UP", mode: "CHASE", homeX: 13, homeY: 13 },
        { id: "clyde", x: 7, y: 8, direction: "DOWN", mode: "CHASE", homeX: 1, homeY: 13 },
    ];
    frightenedUntil = 0;
    tickNumber = 0;
    completed = false;
    constructor() {
        PACMAN_MAP.forEach((row, y) => row.forEach((tile, x) => {
            if (tile === 1)
                this.pills.add(`${x}:${y}`);
            if (tile === 2)
                this.powerPills.add(`${x}:${y}`);
        }));
    }
    syncPlayers(players) {
        players.forEach((player, index) => {
            if (!this.players.has(player.id))
                this.players.set(player.id, {
                    id: player.id, name: player.name, colorHex: player.color,
                    x: 7 + (index % 2), y: 11 + Math.floor(index / 2), direction: "STOP", lives: 3, score: 0,
                });
        });
    }
    input(playerId, direction) {
        const player = this.players.get(playerId);
        if (!player || !["UP", "RIGHT", "DOWN", "LEFT", "STOP"].includes(direction))
            return false;
        player.direction = direction;
        return true;
    }
    tick(now = Date.now()) {
        if (this.completed)
            return;
        this.tickNumber += 1;
        for (const player of this.players.values()) {
            this.moveActor(player, player.direction);
            const key = `${player.x}:${player.y}`;
            if (this.pills.delete(key))
                player.score = (player.score ?? 0) + 10;
            if (this.powerPills.delete(key)) {
                player.score = (player.score ?? 0) + 50;
                this.frightenedUntil = now + 7_000;
            }
        }
        this.ghosts.forEach((ghost, index) => {
            ghost.mode = now < this.frightenedUntil ? "FRIGHTENED" : Math.floor(now / 8_000) % 2 === 0 ? "CHASE" : "SCATTER";
            const target = ghost.mode === "SCATTER"
                ? { x: ghost.homeX, y: ghost.homeY }
                : this.closestPlayer(ghost) ?? { x: 7, y: 7 };
            ghost.direction = this.bestGhostDirection(ghost, target, ghost.mode === "FRIGHTENED", index);
            this.moveActor(ghost, ghost.direction);
        });
        for (const player of this.players.values())
            for (const ghost of this.ghosts) {
                if (player.x !== ghost.x || player.y !== ghost.y)
                    continue;
                if (ghost.mode === "FRIGHTENED") {
                    player.score = (player.score ?? 0) + 200;
                    ghost.x = 7;
                    ghost.y = 7;
                }
                else {
                    player.lives = Math.max(0, (player.lives ?? 0) - 1);
                    player.x = 7;
                    player.y = 11;
                }
            }
        this.completed = this.pills.size === 0 || [...this.players.values()].every((player) => (player.lives ?? 0) <= 0);
    }
    snapshot() {
        return {
            serverTime: Date.now(), tick: this.tickNumber, completed: this.completed, tilemap: PACMAN_MAP,
            pills: [...this.pills], powerPills: [...this.powerPills],
            players: [...this.players.values()], ghosts: this.ghosts,
        };
    }
    moveActor(actor, direction) {
        const [dy, dx] = direction === "UP" ? [-1, 0] : direction === "RIGHT" ? [0, 1]
            : direction === "DOWN" ? [1, 0] : direction === "LEFT" ? [0, -1] : [0, 0];
        const x = actor.x + dx;
        const y = actor.y + dy;
        if (PACMAN_MAP[y]?.[x] && PACMAN_MAP[y][x] !== 0) {
            actor.x = x;
            actor.y = y;
        }
    }
    closestPlayer(actor) {
        return [...this.players.values()].filter((player) => (player.lives ?? 0) > 0)
            .sort((a, b) => manhattan(actor, a) - manhattan(actor, b))[0] ?? null;
    }
    bestGhostDirection(ghost, target, flee, salt) {
        const options = ["UP", "RIGHT", "DOWN", "LEFT"];
        const valid = options.map((direction) => {
            const [dy, dx] = direction === "UP" ? [-1, 0] : direction === "RIGHT" ? [0, 1] : direction === "DOWN" ? [1, 0] : [0, -1];
            return { direction, x: ghost.x + dx, y: ghost.y + dy };
        }).filter(({ x, y }) => PACMAN_MAP[y]?.[x] && PACMAN_MAP[y][x] !== 0);
        valid.sort((a, b) => {
            const first = Math.abs(a.x - target.x) + Math.abs(a.y - target.y);
            const second = Math.abs(b.x - target.x) + Math.abs(b.y - target.y);
            return (flee ? second - first : first - second) || ((a.x + a.y + salt) % 3 - (b.x + b.y + salt) % 3);
        });
        return valid[0]?.direction ?? "STOP";
    }
}
function shuffledBag() {
    const bag = [...PIECE_NAMES];
    for (let index = bag.length - 1; index > 0; index -= 1) {
        const target = Math.floor(Math.random() * (index + 1));
        [bag[index], bag[target]] = [bag[target], bag[index]];
    }
    return bag;
}
function grid(rows, columns, value) {
    return Array.from({ length: rows }, () => Array(columns).fill(value));
}
function manhattan(a, b) {
    return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
}
//# sourceMappingURL=arcadeEngines.js.map