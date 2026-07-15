# Arquitectura y contrato de tiempo real

## Modos de juego

- **Solitario:** Sudoku usa `RandomSudokuGenerator`; los otros nueve juegos usan
  `LocalPuzzleEngine`. Todo se valida dentro de Android, aplica su penalización
  local y persiste tiempo/puntaje por juego mediante `PlayerRecordStore`. No se
  crea `SocketGameClient`. La pausa detiene el cronómetro y difumina el tablero.
- **Multijugador:** Android conecta Socket.IO y solicita crear o unirse a una
  sala. Cada sala contiene su propio `ArenaGame`, solución, jugadores, clima y
  temporizadores; ningún evento se transmite a otras salas.

La configuración online acepta `gameType`: `SUDOKU`, `MINESWEEPER`,
`WORD_SEARCH`, `CROSSWORD`, `NONOGRAM`, `DOTS_AND_BOXES`, `KAKURO`,
`MATHDOKU`, `HITORI` o `RUMMIKUB`. Todos cuentan con práctica solitaria offline.

## Motor matricial genérico

Las nueve arenas adicionales usan `GenericPuzzleEngine`. La respuesta nunca
incluye su matriz privada de soluciones; sólo publica el tablero visible:

```json
{
  "gameId": "room-4821",
  "gameType": "MINESWEEPER",
  "revision": 7,
  "rows": 10,
  "columns": 10,
  "board": [[{
    "value": null,
    "isRevealed": false,
    "ownerId": null,
    "isBlocked": false,
    "meta": {}
  }]],
  "players": [],
  "completed": false,
  "meta": {}
}
```

`meta` representa pistas, jaulas, palabras, aristas o color de una ficha sin
cambiar el contrato base. Las jugadas de humanos, Bots y Ojo de Lince atraviesan
el mismo validador. Buscaminas aplica cinco segundos al tocar una mina y
Timbiriche refleja cada arista en el cuadro adyacente.

## Estado público autoritativo online

Cada sala publica además este estado de lobby/partida:

```json
{
  "roomCode": "4821",
  "hostPlayerId": "socket-host",
  "config": { "gameType": "SUDOKU", "powersEnabled": true, "teamMode": "TWO_V_TWO", "tileType": "COLORS", "botDifficulty": "HARD" },
  "phase": "PLAYING",
  "startedAt": 1783915200000,
  "endsAt": 1783915380000,
  "suddenDeath": false,
  "rematchVotes": 0,
  "pauseRequesterId": null,
  "pauseVotes": 0,
  "pauseRequired": 2,
  "resumeCountdownEndsAt": null
}
```

Modos: `DUEL`, `TWO_V_ONE`, `FFA`, `TWO_V_TWO` y `THREE_V_ONE`. En 2v1/3v1 el Host es Jefe; sus
aciertos base y carga de energía tienen multiplicador x2. El equipo de tres
comparte `teamScore`. En 2v2 ambos integrantes también reciben el mismo total de
equipo y las celdas publican `ownerTeamId` para compartir color/progreso.

El servidor mantiene el tablero, energía, puntuación, bloqueo y clima. Android
sólo envía intenciones y reemplaza su estado local al recibir un snapshot.

```json
{
  "gameId": "arena-main",
  "revision": 42,
  "serverTime": 1783915200000,
  "boardEvent": {
    "type": "GOLDEN_CELLS",
    "startedAt": 1783915200000,
    "endsAt": 1783915210000
  },
  "board": [
    [
      {
        "value": null,
        "ownerId": null,
        "clearing": false,
        "golden": true
      }
    ]
  ],
  "players": [
    {
      "id": "socket-A",
      "name": "Ada",
      "slot": 0,
      "color": "#E53935",
      "score": 230,
      "blockedUntil": 0,
      "energy": 75
    }
  ]
}
```

`board` siempre contiene 9 filas de 9 casillas. La solución completa nunca se
serializa. `revision` aumenta con cada mutación y `serverTime` permite que el
cliente calcule la cuenta regresiva usando el reloj del servidor.

## Eventos Socket.IO

| Dirección | Evento | Payload esencial |
|---|---|---|
| Cliente → servidor | `room:create` | sin payload |
| Cliente → servidor | `room:join` | `{ roomCode }` |
| Cliente → servidor | `room:configure` | `{ gameType, powersEnabled, teamMode, tileType, botDifficulty }` |
| Host → servidor | `fill_with_ai` | Completa el mínimo FFA o los cuatro slots de equipo con Bots |
| Cliente → servidor | `room:start` | sin payload; sólo Host |
| Cliente → servidor | `player:loadout` | `{ powers: ["FOG", "REVEAL"] }`; exactamente dos |
| Cliente → servidor | `room:rematch` | voto de revancha al finalizar |
| Cliente → servidor | `pause:request` | solicita pausa consensuada |
| Cliente → servidor | `pause:respond` | `{ accepted }` |
| Solicitante → servidor | `pause:resume` | inicia cuenta regresiva de 3 segundos |
| Servidor → cliente | `room:joined` | `{ roomCode }` |
| Servidor → sala | `room:state` | configuración, fase y tiempos |
| Servidor → cliente | `room:error` | `{ code, message }` |
| Cliente → servidor | `player:place` | `{ requestId, row, column, value, clientRevision }` |
| Cliente → servidor | `make_move` | `{ requestId, row, col, val }`; arenas no Sudoku |
| Cliente → servidor | `use_power` | `{ type, targetPlayerId?, row?, column?, requestId? }` |
| Cliente → servidor | `send_reaction` | `{ emojiId }` |
| Servidor → cliente | `game:joined` | `{ playerId, roomCode, state }` |
| Servidor → todos | `game:state` | snapshot completo |
| Servidor → todos | `generic:state` | matriz genérica autoritativa |
| Servidor → jugador | `letters:rack` | atril privado, jugador activo y fin del turno de Letras Cruzadas |
| Servidor → cliente | `generic:move-accepted` / `generic:move-rejected` | resultado universal |
| Servidor → cliente | `move:accepted` | `{ requestId, revision, cellPoints, goldenBonus }` |
| Servidor → cliente | `move:rejected` | `{ requestId, code, message }` |
| Servidor → cliente | `player:penalty` | `{ requestId, blockedUntil, reason }` |
| Servidor → todos | `game:section-conquered` | `{ playerId, sections, bonus, clearAt }` |
| Servidor → rival | `power_received` | `{ type: "FOG", attackerId }` |
| Servidor → defensor | `power_reflected` | `{ attackerId }` |
| Servidor → atacante | `power_used` / `power_rejected` | resultado del poder |
| Servidor → todos | `board_event_start` | `{ eventType, startedAt, endsAt }` |
| Servidor → todos | `board_event_end` | `{ eventType }` |
| Servidor → todos | `reaction_received` | `{ reactionId, playerId, emojiId, sentAt }` |
| Servidor → sala | `game:started` | `{ startedAt, endsAt }` |
| Servidor → sala | `game:sudden-death` | `{ endsAt }`; la próxima jugada correcta gana |
| Servidor → sala | `game:finished` | `{ results, finishedAt }` |
| Servidor → sala | `pause:requested` / `pause:started` / `pause:resuming` / `pause:ended` | ciclo autoritativo de pausa |

Emojis admitidos: `LAUGH`, `CRY`, `ANGRY` y `SURPRISED`. El servidor limita las
reacciones a una cada 500 ms por conexión.

## Reglas competitivas

- Cada acierto entrega 10 puntos y 25 de energía, hasta un máximo de 100.
- Los aciertos separados por no más de 4,5 segundos forman combo. Desde el
  cuarto aplican x2 y desde el séptimo x3; un error rompe la cadena.
- Cada jugador equipa exactamente dos poderes en el lobby.
- Niebla cuesta 100 de energía, exige un rival y se devuelve al atacante si el
  objetivo mantiene un Escudo de Espejo activo.
- Escudo de Espejo cuesta 100 y protege durante cinco segundos.
- Ojo de Lince cuesta 50, coloca la solución de la casilla seleccionada desde el
  servidor y concede puntos sin recargar energía por esa colocación automática.
- Cada 45 segundos comienza un evento aleatorio de 10 segundos.
- Hora Espejo entrega 20 puntos por acierto y amplía el bloqueo por error de 3
  a 6 segundos.
- Casillas de Oro marca dos casillas vacías. El primer acierto en cada una suma
  50 puntos adicionales y consume inmediatamente la marca.
- Completar secciones mantiene el bono `100 × cantidad²` y programa su limpieza
  un segundo después.

## Salas, concurrencia y despliegue

Los códigos se generan entre `1000` y `9999`, no se reutilizan mientras la sala
exista y admiten de 2 a 4 participantes (el creador puede esperar solo). La sala
se destruye al salir su último jugador humano. Los listeners que mutan el juego no
contienen `await`: el event loop de Node ordena cada jugada, poder y consumo de
bonificación dentro de cada sala.

### Bots autoritativos

- `fill_with_ai` completa exactamente 2 slots en 1v1, 3 en 2v1 y 4 en 2v2/3v1;
  en FFA completa los espacios libres.
- `EASY`, `MEDIUM` y `HARD` controlan el intervalo y la probabilidad de acierto.
- Cada Bot tiene una personalidad visible: `CALCULATOR` prioriza precisión,
  `TRICKSTER` juega rápido y usa Niebla, y `GUARDIAN` anticipa Escudos.
- El Bot sólo genera una propuesta; `ArenaGame.place` valida y aplica exactamente
  las mismas carreras, puntos, energía, error y bloqueo que para un socket humano.
- Activa Escudo si detecta un rival con energía completa y usa Ojo de Lince tras
  dos intentos fallidos o siete segundos sin progreso.
- Niebla tiene espera inicial de 25–45 segundos, una probabilidad baja por turno
  y un enfriamiento de 40–65 segundos después de cada ataque.
- Tres humanos en 3v1 reciben un Jefe IA. Con un humano, éste es Jefe contra tres
  Bots. Las victorias de Bots no se registran en el Cuadro de Honor.

Esta garantía requiere una sola instancia del backend porque el estado vive en
memoria. Para escalar horizontalmente se necesita un actor por partida o una
operación transaccional en Redis, además del adaptador Redis de Socket.IO.

El Cuadro de Honor sí puede persistir en PostgreSQL configurando `DATABASE_URL`;
sin esa variable usa el archivo JSON atómico. Los récords solitarios globales
consumen un token de desafío de un solo uso emitido al iniciar la partida.

## Responsabilidades Android

- `SocketGameClient`: traduce JSON a eventos de dominio.
- `ArenaViewModel`: recibe `isSoloMode`; usa `SudokuGenerator`/`LocalPuzzleEngine`/`PlayerRecordStore`
  en local o `GameRealtimeGateway` online, sin depender de APIs Android.
- `WelcomeScreen`: guarda nickname y bifurca entre Solitario, Crear Sala y
  Unirse a Sala.
- `SplashScreen`/`ArenaLogo`: identidad Multi Arena para los quince puzzles.
- `RoomLobbyScreen`: configuración sincronizada y editable sólo por el Host.
- `MatchResultsOverlay`: confeti Canvas y tarjetas escalonadas de clasificación.
- `HapticFeedbackController`: usa `VibratorManager`/`Vibrator` según la versión.
- `ArenaScreen`: dibuja oro y clima, anima la limpieza, muestra reacciones y
  captura swipes rápidos del overlay de niebla.
- `ArenaTutorialOverlay`: onboarding paginado independiente para cada juego.
- `PlayerPreferences`: nickname, récord, identidad de reconexión, XP, tutorial y
  marca del reto diario.
