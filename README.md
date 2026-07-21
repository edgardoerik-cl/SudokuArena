# Multi Arena

Prototipo de referencia con servidor autoritativo en Node.js/Socket.IO y cliente
Android nativo en Kotlin/Jetpack Compose.

La versión `8.8.0` reconstruye Flechas en Fuga como un laberinto de rutas
serpenteantes basado en vectores, con validación autoritativa segmento-rayo,
renderizado Canvas/Path, toque sobre toda la trayectoria y extracción animada.
También corrige el envío táctil de grupos en Reactor Chain.
La versión `8.5.0` corrige Ahorcado, peones de Chess y el layout de Capital;
añade Hold/Bomba y bolsa completa a Tetris, Pac-Man con inicio bajo demanda,
fantasmas al 75% y jaula de diez segundos, Drops de Demolición, combos y
cronómetros en Flechas, Letras Cruzadas Blitz y ayudas deterministas para
Hitori/Nurikabe. También incorpora el GDD de Bastión Cooperativo.
La versión `8.4.0` refina Chess Tactics, El Ahorcado, Flechas en Fuga 3D,
Nexo Cero, Pac-Man y Tetris con controles táctiles, habilidades, IA y físicas
adaptadas a sus reglas, además de Bots, dificultad, tutoriales, animaciones y
progreso visual compartido. También
incorpora una auditoría automática de los veinticuatro juegos. La versión `8.2.1`
corrige la URL WebSocket de producción, la pantalla blanca de Flechas,
el teclado virtual de Ahorcado y añade motores offline reales para los arcades y Hotseat
para Damas/Chess Tactics. La versión `8.2.0` incorporó Demolición Arcade, controles virtuales para Pac-Man y Tetris,
Flechas en Fuga con geometría espacial libre, cámara ampliable y poderes contextuales en Capital Arena,
además de validación estricta para Damas, Nexo Cero y Ahorcado. La versión `8.1.0` incorporó animaciones dedicadas para Flechas en Fuga, Chess Tactics RPG,
Pac-Man Arena y El Ahorcado, además de carruseles alfabéticos con portadas locales y favoritos persistentes con DataStore
y una interfaz adaptable que cambia en tiempo real
entre orientación vertical y horizontal, chat global, Piedra/Papel/Tijeras para
decidir el primer turno y veinticuatro juegos:
Sudoku, Buscaminas, Sopa de Letras, Crucigramas, El Gato, Timbiriche,
Kakuro, Mathdoku, Hitori, Chess Tactics RPG, Nurikabe, Bridges, Tetris Arena,
El Ahorcado, Flechas en Fuga, Pac-Man Arena, Letras Cruzadas, Código Secreto,
Capital Arena, Nexo Cero, Damas Clásicas, Demolición Arcade, Memoria Neón y
2048 Arena. Todos comparten salas, colores de conquista,
Bots, energía, poderes y una matriz autoritativa sincronizada por Socket.IO.

Los tableros se generan desde una semilla distinta y admiten dificultad
`EASY`, `MEDIUM`, `HARD` o `EXPERT`. En solitario la generación ocurre en el
teléfono; en multijugador la realiza el servidor para que todos compartan una
única solución autoritativa.

La plataforma también incorpora un menú animado 100% Compose con un nuevo emblema vivo, conquista neón por jugador,
Escudo de Espejo, Ojo de Lince, Bots estratégicos, tema de alto contraste,
Splash Art e icono definitivos,
pantalla inmersiva, fichas de números o colores y perfil persistente,
Cuadro de Honor por juego, lobby configurable, modos 1v1/2v1/FFA/2v2/3v1 y feedback visual
para victoria, error y sabotaje. Las partidas online duran tres minutos y siguen
una máquina de estados autoritativa `LOBBY → PLAYING → SUDDEN_DEATH → FINISHED`.

También incluye un tutorial independiente por juego, avatar y reacciones rápidas,
pausa local con tablero difuminado, pausa online por consenso con cuenta regresiva,
combos autoritativos x2/x3, Bots con personalidad y enfriamiento de sabotajes,
revancha por votación, niveles/XP y reconexión con 15 segundos de tolerancia.

El audio global ofrece cinco pistas musicales reales CC0 —Electrónica, Pop,
Rock, Metal y Clásica— incluidas dentro del APK, con mute persistente, pista
siguiente, selector de género y volumen ajustable. Autores y fuentes:
[`docs/music-credits.md`](docs/music-credits.md).
Capital Arena admite cuatro participantes, cartas de Suerte con movimiento,
bonos, multas o cárcel, fichas neón reacomodables y un tablero horizontal al 75 % con estadísticas laterales.
Tetris Arena usa el randomizador 7-bag y envía líneas basura por combos.
Pac-Man Arena sincroniza un tilemap autoritativo y cuatro fantasmas con estados
Chase, Scatter y Frightened, interpolados a 60 FPS en Compose.

## Estructura

- `backend/`: estado en memoria, validación, concurrencia y tests.
- `android/`: cliente MVVM, adaptador Socket.IO y UI Compose.
- `docs/architecture.md`: esquema JSON, contrato de eventos y decisiones de diseño.

## Arranque rápido

```powershell
cd backend
npm install
npm test
npm run dev
```

Abra `android/` en Android Studio y ejecute `app`. La compilación normal usa
`https://sudokuarena.onrender.com`. Para cambiar el destino explícitamente:

```powershell
cd android
.\build-apk.ps1 -SocketUrl "http://10.0.2.2:3000" # backend local desde emulador
# o
.\build-apk.ps1 -SocketUrl "https://sudokuarena.onrender.com"
```

El script genera un APK de prueba versionado en `android/releases/`, por ejemplo
`MultiArena-v8.9.0.apk`. No se debe subir ese archivo a Git:
es un artefacto generado para instalar en el teléfono.

## Persistencia del Cuadro de Honor

El servidor guarda los Top 10 generales y por cada juego en `backend/data/leaderboards.json`. En una nube
con disco persistente conviene configurar `LEADERBOARD_FILE` apuntando a ese
volumen; por ejemplo, en Bonto: `/data/leaderboards.json`. Sin un volumen
persistente, el ranking se reinicia cuando el contenedor es reemplazado.

Si existe `DATABASE_URL`, el servidor cambia automáticamente a PostgreSQL, crea
la tabla `sudoku_arena_leaderboard` y usa operaciones atómicas para récords y
victorias. `PGSSLMODE=disable` sólo debe usarse en una base local sin TLS.

Los tiempos globales requieren un desafío de un solo uso obtenido al comenzar la
partida. Esto bloquea reenvíos triviales; el récord local continúa funcionando
sin Internet. Para producción comercial conviene validar la partida completa y
usar PostgreSQL. El adaptador ya está incluido; se conserva JSON para no exigir
otro servicio pago durante las pruebas.

El prototipo debe desplegarse con **una sola instancia**: el estado vive en la
memoria del proceso. Para escalar horizontalmente hay que mover el comando de
jugada a un coordinador transaccional (Redis/Lua, actor por partida, etc.) y usar
el adaptador Redis de Socket.IO.
