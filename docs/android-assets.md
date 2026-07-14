# Recursos gráficos Android

## Icono del APK

La aplicación ya declara en `AndroidManifest.xml`:

```xml
android:icon="@mipmap/ic_launcher"
android:roundIcon="@mipmap/ic_launcher"
```

La vía recomendada es Android Studio: clic derecho sobre `app/src/main/res` →
**New → Image Asset → Launcher Icons (Adaptive and Legacy)**. Selecciona el arte
del escudo como foreground, usa `#0F111A` como background y conserva el nombre
`ic_launcher`. Android Studio generará y reemplazará todas las densidades.

Si se hace manualmente, coloca el icono legado `ic_launcher.png` así:

| Carpeta | Tamaño |
|---|---:|
| `mipmap-mdpi/` | 48×48 px |
| `mipmap-hdpi/` | 72×72 px |
| `mipmap-xhdpi/` | 96×96 px |
| `mipmap-xxhdpi/` | 144×144 px |
| `mipmap-xxxhdpi/` | 192×192 px |

Para conservar el icono adaptativo en Android 8+, reemplaza también
`drawable/ic_launcher_foreground.xml` por las capas generadas por Image Asset,
o mantén el XML actual. El archivo `mipmap-anydpi-v26/ic_launcher.xml` integra
foreground y background. Un PNG ya aplanado puede perder parte de sus bordes al
ser recortado por máscaras circulares; deja el escudo dentro del 66% central.

## Splash Art vertical

El arte final se encuentra en:

```text
app/src/main/res/drawable-nodpi/sudoku_arena_splash_art.png
```

`nodpi` evita que Android reescale el bitmap por densidad; Compose lo recorta con
`ContentScale.Crop` para llenar cualquier proporción sin deformarlo.
