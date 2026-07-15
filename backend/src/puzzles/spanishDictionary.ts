export interface SpanishWord { word: string; clue: string }

/** Diccionario integrado: evita que una API externa caída bloquee una partida. */
export const SPANISH_DICTIONARY: readonly SpanishWord[] = [
  { word: "ARENA", clue: "Lugar de competición" }, { word: "LOGICA", clue: "Razonamiento correcto" },
  { word: "MATRIZ", clue: "Conjunto rectangular" }, { word: "PUZZLE", clue: "Rompecabezas" },
  { word: "MENTE", clue: "Capacidad de pensar" }, { word: "CLAVE", clue: "Dato para descifrar" },
  { word: "CIFRA", clue: "Símbolo numérico" }, { word: "ISLA", clue: "Tierra rodeada de agua" },
  { word: "PUENTE", clue: "Une dos orillas" }, { word: "LAZO", clue: "Línea cerrada" },
  { word: "COLOR", clue: "Percepción visual" }, { word: "NEON", clue: "Luz intensa" },
  { word: "EQUIPO", clue: "Grupo que coopera" }, { word: "RIVAL", clue: "Oponente" },
  { word: "RETO", clue: "Desafío" }, { word: "NIVEL", clue: "Grado de dificultad" },
  { word: "SUMA", clue: "Operación de adición" }, { word: "CELDA", clue: "Unidad de una cuadrícula" },
  { word: "FICHA", clue: "Pieza de juego" }, { word: "PISTA", clue: "Ayuda para resolver" },
  { word: "RIO", clue: "Corriente de agua" }, { word: "MINA", clue: "Peligro oculto" },
  { word: "BOMBA", clue: "Explosivo" }, { word: "TRAZO", clue: "Línea dibujada" },
  { word: "JUGADA", clue: "Acción durante una partida" }, { word: "VICTORIA", clue: "Resultado de ganar" },
  { word: "ESCUDO", clue: "Protección defensiva" }, { word: "NIEBLA", clue: "Reduce la visibilidad" },
  { word: "ENERGIA", clue: "Recurso para poderes" }, { word: "COMBO", clue: "Cadena de aciertos" },
  { word: "CAMINO", clue: "Ruta entre puntos" }, { word: "BLOQUE", clue: "Conjunto compacto" }
] as const;
