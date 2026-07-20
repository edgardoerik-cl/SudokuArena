# Multi Arena: Bastión Cooperativo — GDD de mecánicas

## Visión

Tower Defense 2D cooperativo para 2–4 jugadores, con partidas de 18–25 minutos.
La lectura del campo debe ser inmediata: los enemigos no muestran barras de vida;
su apariencia, tamaño y color comunican la capa que todavía conservan. El reto
surge de coordinar cobertura, economía, detección y control de masas.

## Bucle principal

1. Fase de preparación de 20 segundos: construir, mejorar, vender y transferir.
2. Oleada: los enemigos recorren uno o varios caminos hacia el Núcleo.
3. Cada impacto elimina capas; al perder una capa el enemigo se transforma.
4. La escuadra cobra recompensas, repara errores de cobertura y prepara la siguiente oleada.
5. La partida termina al superar la oleada final o perder las 100 cargas del Núcleo.

Las acciones de construcción y mejora son autoritativas en el servidor. Los
proyectiles visuales pueden predecirse en el cliente, pero daño, dinero y
transformaciones de capa se resuelven en un tick fijo de 20 Hz.

## Enemigos: salud por capas

Cada enemigo tiene `layer`, `speed`, `tags`, `childrenOnBreak` y `leakDamage`.
Un punto de daño elimina una capa. El daño sobrante continúa hacia las capas
inferiores, salvo que el ataque indique `noOverflow`. Romper una capa puede
producir enemigos hijos, por lo que una explosión grande no siempre reemplaza
la necesidad de cadencia.

| Enemigo | Capas | Velocidad | Al romperse | Función |
|---|---:|---:|---|---|
| Chispa | 1 | 1,35× | Desaparece | Unidad básica y veloz |
| Prisma | 2 | 1,15× | 1 Chispa | Presenta la transformación |
| Núcleo | 3 | 1,00× | 1 Prisma | Base del juego medio |
| Colmena | 4 | 0,82× | 2 Prismas | Castiga daño sin área |
| Titán | 8 | 0,55× | 2 Colmenas | Objetivo fuerte y lento |

Modificadores:

- **Velado:** invisible para torres sin Detección. Su silueta parpadea para que
  el jugador entienda por qué no recibe disparos.
- **Acorazado:** ignora daño Cortante y reduce 50% el daño Cinético; Explosivo y
  Perforante rompen el blindaje.
- **Rúnico:** inmune a Fuego y Magia. El estado Mojado, aplicado por soporte,
  desactiva temporalmente esa inmunidad.

Los modificadores pueden combinarse desde la oleada 14, con un máximo de dos
por enemigo antes del modo infinito.

## Torres y puntería

### Artillero de Plasma

Daño circular medio, cadencia lenta y alcance medio. Excelente contra Colmenas.
No detecta Velados inicialmente.

### Tirador Vectorial

Alcance global, daño Perforante alto, cadencia baja. Prioriza capas altas y
atraviesa una unidad después de la primera mejora.

### Aguja Cinética

Cadencia muy alta, daño de una capa y alcance corto. Elimina los hijos rápidos
que dejan los enemigos grandes.

### Faro Crono

No inflige daño base. Ralentiza, revela Velados dentro de su radio y puede
potenciar alcance o cadencia de cualquier aliado, sin importar quién sea su dueño.

Cada torre puede cambiar en tiempo real entre:

- **Primero:** mayor progreso normalizado en el camino.
- **Último:** menor progreso; útil para limpiar filtraciones.
- **Más fuerte:** mayor suma de capas y modificadores.
- **Más cercano:** menor distancia euclidiana a la torre.

El cambio de prioridad es inmediato y gratuito, pero la selección de objetivo
solo se recalcula en el siguiente tick para mantener determinismo.

## Árbol del Artillero de Plasma

Tras dos mejoras comunes (`Alcance I`, `Refrigeración I`), el jugador elige una
rama irreversible. Vender devuelve 70% del coste, pero no conserva la rama.

### Rama A — Cataclismo

1. **Carga densa:** +2 capas de daño, −15% cadencia.
2. **Onda expansiva:** radio de explosión +45%.
3. **Ruptura total:** el daño sobrante atraviesa hasta dos transformaciones y
   elimina Acorazado.

### Rama B — Tormenta

1. **Doble condensador:** +35% cadencia, −1 capa de daño.
2. **Arco encadenado:** alcanza dos objetivos secundarios.
3. **Sobrecarga cooperativa:** cada torre de otro jugador dentro del radio
   aporta +8% cadencia, hasta 32%.

## Economía cooperativa

Modelo híbrido:

- 60% de cada recompensa va al jugador cuya torre produjo el último impacto.
- 40% entra al **Fondo de Escuadra**, visible para todos y utilizable mediante
  votación rápida para mejoras globales, reparación o detección de emergencia.
- Cada jugador puede transferir dinero individual en múltiplos de 50, con un
  cooldown de 5 segundos para impedir spam.
- Las asistencias de soporte otorgan una recompensa pequeña al dueño del Faro,
  evitando que el rol de apoyo quede económicamente atrás.

Las mejoras globales del Fondo incluyen: +5 cargas del Núcleo, visión Velada
durante una oleada o reducción del 10% al coste de la próxima torre de cada jugador.

## Espacio y antimonopolio

El mapa no divide zonas rígidas. Usa **créditos de ocupación**:

- Cada jugador dispone de 5 créditos personales al inicio.
- Una torre consume 1; una torre de alcance global consume 2.
- Los nodos premium cercanos a curvas consumen un crédito adicional.
- Al llegar a cero, el jugador debe mejorar, vender o pedir que un aliado
  construya. Así nadie llena solo todas las curvas óptimas.
- El equipo conserva 4 nodos comunitarios donde solo se permiten torres de
  soporte; colocar una requiere confirmación de otro jugador.

Una torre vendida libera su crédito inmediatamente. Los marcadores de colocación
muestran verde para espacio válido, amarillo para nodo premium y rojo para zona
sin línea de visión.

## Sinergias

- Mojado del Faro + Plasma Cataclismo: elimina inmunidad Rúnica.
- Ralentización + Tirador Vectorial: aumenta 20% la probabilidad de perforación.
- Aguja Cinética dentro de Onda expansiva: sus impactos acumulan carga; al llegar
  a diez, el siguiente disparo del Artillero explota gratis.
- Los jugadores pueden marcar un enemigo prioritario durante 4 segundos. Todas
  las torres en modo “Primero” que puedan alcanzarlo lo consideran primero.
- Una habilidad de escuadra por partida, **Tiempo Muerto**, ralentiza el mundo
  al 30% durante 3 segundos sin alterar los cooldowns del servidor.

## Curva de las primeras 20 oleadas

| Oleada | Composición y propósito | Presupuesto relativo |
|---:|---|---:|
| 1 | 18 Chispas espaciadas; enseña trayectoria | 1,0 |
| 2 | 26 Chispas en grupos | 1,2 |
| 3 | 12 Prismas; primera transformación | 1,5 |
| 4 | Prismas + ráfaga final de Chispas | 1,8 |
| 5 | 10 Núcleos; exige mejorar daño | 2,1 |
| 6 | Dos caminos simultáneos | 2,5 |
| 7 | Primeros **Velados**; obliga a comprar Detección | 2,9 |
| 8 | Velados mezclados con Núcleos | 3,3 |
| 9 | Primera Colmena; enseña enemigos hijos | 3,8 |
| 10 | Mini-jefe Titán con 12 capas y premio de equipo | 4,5 |
| 11 | Oleada rápida de 70 Chispas | 4,8 |
| 12 | Primeros **Acorazados** | 5,3 |
| 13 | Colmenas Acorazadas + Chispas | 5,9 |
| 14 | Primeros **Rúnicos**; exige daño físico/Mojado | 6,5 |
| 15 | Tres rutas y pausa de preparación reducida a 15 s | 7,2 |
| 16 | Velado + Acorazado en Núcleos | 8,0 |
| 17 | Dos Titanes escoltados por Rúnicos | 9,0 |
| 18 | Oleada de resistencia sin descanso intermedio | 10,2 |
| 19 | Mezcla adaptativa según la debilidad del equipo | 11,5 |
| 20 | Jefe “Arquitecto”: 30 capas, invoca Colmenas al 75/50/25% | 14,0 |

El servidor calcula el presupuesto real como:

`baseWaveBudget × (1 + 0,65 × (jugadores - 1))`

No se multiplica linealmente porque cuatro jugadores pierden eficiencia al
coordinar espacio y economía. En solitario se concede 15% más dinero inicial.

## Progresión sin ventaja de pago

La progresión desbloquea alternativas, no poder acumulativo:

- Experiencia de cuenta abre nuevas torres y variantes laterales.
- Cada partida comienza con estadísticas base idénticas.
- Maestría de torre desbloquea aspectos, animaciones, sonidos y prioridades
  preconfiguradas.
- Desafíos semanales modifican reglas y entregan cosméticos.
- No se venden capas de daño, dinero inicial ni espacios de construcción.

## Telemetría de balance

Registrar por oleada: fugas, dinero sin gastar, daño por torre, asistencias,
objetivos sin detectar, ocupación de nodos premium y momento de primera derrota.
Alertas de balance:

- Una torre supera 38% del daño total en más de 60% de partidas.
- Una oleada provoca más de 25% de abandonos.
- Un jugador concentra más de 55% de nodos premium.
- El Fondo de Escuadra termina sin utilizar en más de 40% de partidas.

