# Sudoku Arena: Robo de Filas

Prototipo de referencia con servidor autoritativo en Node.js/Socket.IO y cliente
Android nativo en Kotlin/Jetpack Compose.

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
`SudokuArena-RoboDeFilas-v0.2.0-debug.apk`. No se debe subir ese archivo a Git:
es un artefacto generado para instalar en el teléfono.

El prototipo debe desplegarse con **una sola instancia**: el estado vive en la
memoria del proceso. Para escalar horizontalmente hay que mover el comando de
jugada a un coordinador transaccional (Redis/Lua, actor por partida, etc.) y usar
el adaptador Redis de Socket.IO.
