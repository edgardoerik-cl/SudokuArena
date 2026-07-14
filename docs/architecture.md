# Arquitectura y contrato de tiempo real

## Modos de juego

- **Solitario:** `RandomSudokuGenerator` crea una solución y 45 casillas vacías
  dentro de Android. `ArenaViewModel` valida localmente, aplica el bloqueo de 3
  segundos y persiste nickname/récord mediante `PlayerRecordStore`. No se crea
  `SocketGameClient`.
- **Multijugador:** Android conecta Socket.IO y solicita crear o unirse a una
  sala. Cada sala contiene su propio `ArenaGame`, solución, jugadores, clima y
  temporizadores; ningún evento se transmite a otras salas.

## Estado público autoritativo online

Cada sala publica además este estado de lobby/partida:

```json
{
  "roomCode": "4821",
  "hostPlayerId": "socket-host",
  "config": { "powersEnabled": true, "teamMode": "TWO_V_TWO" },
  "phase": "PLAYING",
  "startedAt": 1783915200000,
  "endsAt": 1783915380000
}
```

Modos: `FFA`, `TWO_V_TWO` y `THREE_V_ONE`. En 3v1 el Host es Jefe; sus
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
| Cliente → servidor | `room:configure` | `{ powersEnabled, teamMode }` |
| Cliente → servidor | `room:start` | sin payload; sólo Host |
| Servidor → cliente | `room:joined` | `{ roomCode }` |
| Servidor → sala | `room:state` | configuración, fase y tiempos |
| Servidor → cliente | `room:error` | `{ code, message }` |
| Cliente → servidor | `player:place` | `{ requestId, row, column, value, clientRevision }` |
| Cliente → servidor | `use_power` | `{ targetPlayerId }` |
| Cliente → servidor | `send_reaction` | `{ emojiId }` |
| Servidor → cliente | `game:joined` | `{ playerId, roomCode, state }` |
| Servidor → todos | `game:state` | snapshot completo |
| Servidor → cliente | `move:accepted` | `{ requestId, revision, cellPoints, goldenBonus }` |
| Servidor → cliente | `move:rejected` | `{ requestId, code, message }` |
| Servidor → cliente | `player:penalty` | `{ requestId, blockedUntil, reason }` |
| Servidor → todos | `game:section-conquered` | `{ playerId, sections, bonus, clearAt }` |
| Servidor → rival | `power_received` | `{ type: "FOG", attackerId }` |
| Servidor → atacante | `power_used` / `power_rejected` | resultado del poder |
| Servidor → todos | `board_event_start` | `{ eventType, startedAt, endsAt }` |
| Servidor → todos | `board_event_end` | `{ eventType }` |
| Servidor → todos | `reaction_received` | `{ reactionId, playerId, emojiId, sentAt }` |
| Servidor → sala | `game:started` | `{ startedAt, endsAt }` |
| Servidor → sala | `game:finished` | `{ results, finishedAt }` |

Emojis admitidos: `LAUGH`, `CRY`, `ANGRY` y `SURPRISED`. El servidor limita las
reacciones a una cada 500 ms por conexión.

## Reglas competitivas

- Cada acierto entrega 10 puntos y 25 de energía, hasta un máximo de 100.
- Niebla cuesta 100 de energía, exige un rival conectado y no permite atacarse
  a sí mismo.
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
se destruye al salir su último jugador. Los listeners que mutan el juego no
contienen `await`: el event loop de Node ordena cada jugada, poder y consumo de
bonificación dentro de cada sala.

Esta garantía requiere una sola instancia del backend porque el estado vive en
memoria. Para escalar horizontalmente se necesita un actor por partida o una
operación transaccional en Redis, además del adaptador Redis de Socket.IO.

## Responsabilidades Android

- `SocketGameClient`: traduce JSON a eventos de dominio.
- `ArenaViewModel`: recibe `isSoloMode`; usa `SudokuGenerator`/`PlayerRecordStore`
  en local o `GameRealtimeGateway` online, sin depender de APIs Android.
- `WelcomeScreen`: guarda nickname y bifurca entre Solitario, Crear Sala y
  Unirse a Sala.
- `SplashScreen`/`ArenaLogo`: identidad visual 100% Canvas, sin recursos bitmap.
- `RoomLobbyScreen`: configuración sincronizada y editable sólo por el Host.
- `MatchResultsOverlay`: confeti Canvas y tarjetas escalonadas de clasificación.
- `HapticFeedbackController`: usa `VibratorManager`/`Vibrator` según la versión.
- `ArenaScreen`: dibuja oro y clima, anima la limpieza, muestra reacciones y
  captura swipes rápidos del overlay de niebla.
