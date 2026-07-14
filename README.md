# Sudoku Arena

Prototipo de referencia con servidor autoritativo en Node.js/Socket.IO y cliente
Android nativo en Kotlin/Jetpack Compose.

La versión `0.9.0` incorpora un menú animado 100% Compose, conquista neón por jugador,
Escudo de Espejo, Ojo de Lince, Bots estratégicos, tema de alto contraste,
Splash Art e icono definitivos,
pantalla inmersiva, fichas de números o colores y perfil persistente,
Cuadro de Honor global, lobby configurable, modos FFA/2v2/3v1 y feedback visual
para victoria, error y sabotaje. Las partidas online duran tres minutos y siguen
una máquina de estados autoritativa `LOBBY → PLAYING → FINISHED`.

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

Abra `android/` en Android Studio y ejecute `app`. El emulador usa por defecto
`http://10.0.2.2:3000`. Para una URL desplegada:

```powershell
cd android
.\build-apk.ps1 -SocketUrl "https://tu-servidor.ejemplo"
```

El script genera un APK de prueba versionado en `android/releases/`, por ejemplo
`SudokuArena-v0.9.0-debug.apk`. No se debe subir ese archivo a Git:
es un artefacto generado para instalar en el teléfono.

## Persistencia del Cuadro de Honor

El servidor guarda los Top 10 en `backend/data/leaderboards.json`. En una nube
con disco persistente conviene configurar `LEADERBOARD_FILE` apuntando a ese
volumen; por ejemplo, en Bonto: `/data/leaderboards.json`. Sin un volumen
persistente, el ranking se reinicia cuando el contenedor es reemplazado.

El prototipo debe desplegarse con **una sola instancia**: el estado vive en la
memoria del proceso. Para escalar horizontalmente hay que mover el comando de
jugada a un coordinador transaccional (Redis/Lua, actor por partida, etc.) y usar
el adaptador Redis de Socket.IO.
