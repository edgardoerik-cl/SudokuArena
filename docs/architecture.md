# Arquitectura y contrato de tiempo real

## Esquema público del estado

El servidor conserva internamente `Set<string>` de tokens de limpieza por
casilla. Esos tokens no salen por red; el cliente solo recibe `clearing`.

```json
{
  "gameId": "arena-main",
  "revision": 42,
  "serverTime": 1783915200000,
  "board": [
    [
      { "value": 5, "ownerId": "socket-A", "clearing": false },
      { "value": null, "ownerId": null, "clearing": false }
    ]
  ],
  "players": [
    {
      "id": "socket-A",
      "name": "Ada",
      "slot": 0,
      "color": "#E53935",
      "score": 230,
      "blockedUntil": 0
    }
  ]
}
```

`board` siempre contiene 9 filas de 9 casillas. `blockedUntil` y `serverTime`
son epoch milliseconds; el cliente calcula un pequeño offset de reloj al recibir
cada snapshot. `revision` crece con cada mutación autoritativa.

La solución completa no se serializa. Permanece únicamente en el servidor y
permite validar una casilla aun después de que una sección haya sido vaciada.

## Eventos

| Dirección | Evento | Payload esencial |
|---|---|---|
| Cliente → servidor | `player:place` | `{ requestId, row, column, value, clientRevision }` |
| Servidor → cliente | `game:joined` | `{ playerId, state }` |
| Servidor → todos | `game:state` | snapshot completo |
| Servidor → cliente | `move:accepted` | `{ requestId, revision }` |
| Servidor → cliente | `move:rejected` | `{ requestId, code, message }` |
| Servidor → cliente | `player:penalty` | `{ requestId, blockedUntil, reason }` |
| Servidor → todos | `game:section-conquered` | `{ playerId, sections, bonus, clearAt }` |

Códigos de rechazo: `INVALID_PAYLOAD`, `PLAYER_NOT_FOUND`, `BLOCKED`,
`CELL_OCCUPIED`, `CELL_CLEARING`, `INCORRECT_VALUE` y `DUPLICATE_REQUEST`.

## Flujo y concurrencia

1. Android envía una intención y mantiene la casilla como pendiente.
2. El listener del servidor valida y muta el tablero sin ningún `await` entre
   lectura y escritura. El event loop de Node procesa esos callbacks uno por uno.
3. La primera intención recibida ocupa la casilla e incrementa `revision`; la
   siguiente observa la casilla ocupada y recibe `CELL_OCCUPIED`.
4. El snapshot se emite después de la mutación. Android reemplaza su tablero,
   en vez de intentar fusionar cambios localmente.
5. Una sección completa se marca `clearing` y se vacía tras 1 segundo. Tokens
   internos bloquean sus casillas durante limpiezas solapadas, evitando que un
   temporizador anterior borre una jugada posterior.

Esta garantía solo cubre un proceso Node. Con varias réplicas, el orden global
debe imponerse fuera del proceso.

## Puntuación del prototipo

- Casilla correcta: 10 puntos.
- Secciones completadas por la misma jugada: `100 × cantidad²` puntos. Así, una
  jugada que completa fila y columna entrega 400 puntos de conquista.

El “mazo recargable” queda detrás de un futuro contrato privado
`player:inventory`: no se inventó todavía una cadencia/capacidad porque esas
reglas afectan directamente el balance. En este MVP los dígitos 1–9 son
ilimitados.
