# FOSScore

> Lector de partituras FOSS para Android.
> Inspirado en una app de iOS similar, pero libre y sin anuncios.

## Features

Hecho:

- [x] **Visor PDF** optimizado para partituras (MuPDF) — retrato, paisaje, two-up, half-page turns, zoom/pan con ancla al punto medio, doble toque animado
- [x] **Biblioteca con metadatos** — compositor, género, tonalidad, etiquetas
- [x] **Anotaciones de tinta exportables al PDF** (`/Annots` Ink, botón 💾PDF en modo edición)
- [x] **Tema oscuro** — claro/oscuro/automático, atenuado de página, modo negativo para OLED

Pendiente (roadmap detallado en los issues de GitHub):

- [ ] **Reflow** — detección automática de sistemas musicales para visualización tipo teleprompter horizontal
- [ ] **Búsqueda y filtros** — browsing dinámico sin carpetas
- [ ] **Setlists** — agrupa partituras para tocarlas en orden, con navegación entre archivos
- [ ] **Sellos y texto** — sellos musicales también a `/Annots`, notas de texto
- [ ] **Pistas de audio** — asocia audio a cada partitura, ajusta velocidad/tono, loop, cambio de página automático
- [ ] **Herramientas integradas** — metrónomo, afinador, diapasón, piano virtual
- [ ] **MIDI / Bluetooth** — pedales de cambio de página (PageFlip, AirTurn, etc.)
- [ ] **Marcadores y banderas** — navegación rápida dentro de archivos largos
- [ ] **Enlaces y botones** — repeticiones, acciones programables en la página
- [ ] **Reordenar páginas** — insertar, duplicar, eliminar, dividir archivos
- [ ] **Performance mode, dashboard de práctica**
- [ ] **100 % offline first** — tu música siempre contigo
- [ ] **Sin telemetría, sin anuncios, sin cuentas obligatorias**

## Compatibilidad

- Android 6.0+ (minSdk 23), incluidas tablets viejas de 32 bits (`armeabi-v7a`).
- 100 % offline: sin cuentas, sin telemetría, sin anuncios.

## Stack

- **Lenguaje:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **Renderizado PDF:** MuPDF (`com.artifex.mupdf:fitz`, AAR prebuilt con el JNI; no hace falta NDK)
- **Base de datos:** Room (SQLite)
- **Audio:** ExoPlayer / Media3
- **MIDI:** framework MIDI de Android

## Licencia

**AGPL-3.0** (ver [LICENSE](LICENSE)). Copyright (c) 2026 Alberto.

FOSScore enlaza **MuPDF**, que es AGPL-3.0: la obra combinada tiene que distribuirse bajo AGPL.
Consecuencias prácticas:

- El código fuente completo de la app es público.
- No podemos usar librerías propietarias de Google (Play Services, AdMob, Crashlytics, ML Kit).
- Para usar esto en software cerrado hay que comprar la licencia comercial a Artifex.

## Estado

En desarrollo temprano. La rama `dev` es la integración; `main` siempre instalable.

## Contribuir

- Trabaja sobre `dev`, con PRs pequeños e independientes (una idea por PR).
- Cada push a `dev`/`main` corre el CI: `assembleDebug` + `lintDebug` + `testDebugUnitTest`.
- PRs, issues y sugerencias bienvenidas.
