# Pantalla de inicio moderna (Fire TV / Android TV)

Inicio estilo plataforma de streaming para el cliente Android TV de Jellyfin: barra de navegación en
píldora, carrusel principal, filas con números de tendencia y etiquetas de **New episode / New season**,
calidad (Dolby Vision, Atmos, HDR, 4K…) y fecha de estreno.

## Qué incluye

| Fila | Origen de los datos |
|---|---|
| Carrusel principal | Películas y series aleatorias con fondo (prioriza las que tienen logo) |
| Continue Watching | Episodios/películas a medias y siguientes episodios mezclados, ordenados por lo último que viste (para el siguiente episodio cuenta la última vez que viste la serie) |
| Watchlist | Películas y series marcadas con el botón de watchlist (ver más abajo) |
| Trending this week | Lo más visto en tu servidor en los últimos 7 días (todos los usuarios si eres administrador). Los huecos se rellenan con las tendencias de TMDB (si hay clave) y con lo mejor valorado reciente |
| Latest Episodes | Episodios por fecha de emisión (una tarjeta por serie, con la fecha) |
| Latest Films | Películas por fecha de estreno, con la fecha |
| Recently added movies / shows | Por fecha de añadido al servidor |
| Favourites | Tus favoritos |

Reglas de las etiquetas rojas (últimos 14 días, `MediaBadges.NEW_CONTENT_WINDOW_DAYS`):

- Se añadió el episodio 1 de la temporada 1 → **New series**
- Se añadió el episodio 1 de otra temporada → **New season**
- Cualquier otro episodio → **New episode**

Si una película o serie está en varias bibliotecas (por ejemplo, una por idioma) solo aparece una vez por fila.

### Etiquetas de calidad y banderas de idioma

- Arriba a la izquierda, en este orden: la **resolución** (4K, 1080p, 720p, SD) en un bloque blanco con las letras
  transparentes, **Dolby** con **ATMOS / DIGITAL / TRUEHD · VISION** debajo, y **HDR10+ / HDR / HLG** en blanco.
- Arriba a la derecha: episodios sin ver (círculo) o la fecha de estreno en "Latest releases".
- Abajo a la izquierda: hasta **3 banderas** con los idiomas de audio del archivo. Primero inglés, español y
  japonés (en ese orden) y después el resto en el orden del archivo. El español latino (pista con
  "Latino"/"Latin" en el título o etiqueta `es-419`/`es-MX`) lleva bandera de México y el portugués de Brasil
  la de Brasil. Idiomas sin bandera se muestran con su código ("ARA").
- En las series se usan los idiomas y la calidad de uno de sus episodios.

## Orden de las filas del inicio

Ajustes → **Secciones del inicio** (también en Personalización → Inicio): selecciona una sección con OK, muévela
con arriba/abajo y pulsa OK otra vez para soltarla. *Restablecer el orden por defecto* vuelve al orden original.

## Bibliotecas: mover y ocultar

- **Mantén pulsado OK** sobre una biblioteca de la barra flotante → *Mover*: con izquierda/derecha cambia de sitio,
  OK (o Atrás) para terminar. El orden se guarda en el dispositivo.
- **Mantén pulsado OK** → *Ocultar*.
- Una biblioteca oculta desaparece de la barra, del inicio (todas sus filas) y de la búsqueda. Útil si
  tienes las mismas películas dos veces (por ejemplo "Films" en inglés y "Películas" en español).
- Para volver a mostrarla: Ajustes → **Hidden libraries / Bibliotecas ocultas** (también en
  Personalización → Inicio). Se guardan en el dispositivo.

## Watchlist

Botón con el icono de marcador junto a ⋮ en las fichas (en una temporada se añade la serie entera). Los títulos
aparecen en la fila **Watchlist** del inicio y en la página Watchlist (botón del marcador junto al engranaje,
en la barra de navegación). Se guarda en el servidor como "Me gusta" (*Likes*), el mismo campo
que usa la watchlist de KefinTweaks en la web, así que las dos están sincronizadas.

## Música de la serie

En la ficha de una serie y en sus temporadas suena la sintonía de la serie (con fundido de entrada y salida).
Sigue sonando al pasar de la serie a una temporada y se para al reproducir, al salir de la serie o al salir
de la app. Suena una vez, no en bucle.

- La serie necesita una canción de tema en el servidor: un archivo `theme.mp3` en la carpeta de la serie
  (o descargado con el plugin *Theme Songs*).
- No suena si estás escuchando música en la app.
- Se desactiva en Ajustes → Personalización → Inicio → **Música de la serie**. El volumen está en
  `ThemeMusicPlayer.kt` (`MAX_VOLUME`).

## Biblioteca de música

Al abrir una biblioteca de música desde la barra se muestra una página con el mismo estilo que el inicio:

- **Media bar** con álbumes recomendados ("Porque escuchas a …"): Reproducir, **Radio** (mezcla
  instantánea a partir del álbum) e Ir al álbum. Izquierda/derecha en los botones de los extremos cambian
  de recomendación; pasan solas cada 10 segundos cuando el foco no está en la barra.
- Accesos rápidos: Álbumes, Artistas del álbum, Artistas, Géneros y Aleatorio.
- Filas: Añadido recientemente, Escuchado recientemente, **Radios**, Recomendado para ti, Álbumes
  favoritos y Listas de reproducción. El orden se cambia en Ajustes → **Secciones de música** (igual que
  las del inicio).
- En las tarjetas: OK abre el álbum o la lista (en canciones y radios, reproduce); el botón
  play/pausa del mando reproduce directamente; mantener OK en una canción abre su álbum.

Las recomendaciones y las radios salen del propio servidor de Jellyfin, sin servicios externos:

- Se miran las canciones que más escuchas (número de reproducciones). Los álbumes más escuchados sirven
  de "semilla" y se piden álbumes parecidos (`/Albums/{id}/Similar`), quitando los que ya escuchas.
- Las radios son mezclas instantáneas de Jellyfin (`InstantMix`): "Tu mix" a partir de tu álbum más
  escuchado, una radio por cada uno de tus 4 artistas más escuchados y otra por tus 4 géneros favoritos.
- Si todavía no hay nada escuchado, se muestran álbumes al azar y géneros de la biblioteca.
- Las sugerencias mejoran con buenos metadatos (géneros y artistas bien etiquetados, p. ej. con
  MusicBrainz). Opcionalmente, el plugin de ListenBrainz/Last.fm del servidor registra lo que escuchas,
  pero no hace falta para esta página.

Se desactiva en Ajustes → Personalización → Inicio → **Página de música moderna** (vuelve la página clásica).
El código está en `app/src/main/java/org/jellyfin/androidtv/ui/music/modern/`.

## Fichas de películas, series, temporadas y personas

"More info" (y cualquier ficha de la app) abre la nueva página para películas, series, temporadas y
personas: fondo a pantalla completa con logo, valoración, duración/temporadas, géneros, sinopsis y
botones (*Play*, favoritos, tráiler, visto, información del archivo, watchlist y ⋮ para la ficha clásica con
todas las opciones). Debajo: *Next up*, temporadas, episodios, reparto y títulos similares.

En series y temporadas el botón muestra el episodio: **Resume S01:E05** si ya lo empezaste, **Play S02:E01**
si no (en español, *Reanudar T01:E05* / *Reproducir T02:E01*). En una temporada los episodios salen en un
carrusel que empieza en el siguiente por ver (marcado como *Next up*). Se reproducen con OK o con ⏯;
manteniendo OK se abre su ficha clásica.

Se puede desactivar en Ajustes → Personalización → Inicio → **Fichas modernas**. Los demás tipos
(episodios, álbumes, colecciones…) siguen usando la ficha clásica.

## Reproductor

Controles nuevos para películas y episodios (la TV en directo mantiene los originales, con la guía):

- **Reproduciendo**: sin nada en pantalla. **OK** pausa, **izquierda/derecha** avanzan o retroceden (mantener
  pulsado acelera) con miniatura de la escena, **arriba/abajo** muestran los controles.
- **Controles**: título y logo arriba; barra de progreso (OK reproduce/pausa) con los
  capítulos marcados con líneas, tiempo transcurrido y restante; episodio anterior / siguiente y "Termina a las…"
  abajo a la izquierda; abajo a la derecha **audio**, **subtítulos**, **escenas**, **calidad**, **velocidad** y
  **desfase de subtítulos**. En pausa se ven dos barras discretas en el centro. Se ocultan solos a los 5 segundos; **Atrás** también los oculta.
- **En pausa** (sin controles): "Estás viendo" con el logo, temporada y episodio, año, duración, valoración y
  sinopsis.
- **Saltar intro / Saltar créditos / Saltar resumen**: usa los segmentos del servidor (plugin *Intro Skipper* o
  segmentos de Jellyfin 10.10+) cuando su acción es "Preguntar" en los ajustes de reproducción (segmentos multimedia). OK salta.
- **Desfase de subtítulos**: de ±0,1 s o ±0,5 s, se reinicia en cada reproducción. No afecta a los subtítulos
  ASS/SSA con estilos (los dibuja otra librería).

Se desactiva en Ajustes → Personalización → Inicio → **Controles modernos del reproductor**.

**Arranque rápido**: el inicio guarda lo que mostró la última vez y lo enseña al momento al abrir la app; las
filas nuevas se cargan por detrás y el carrusel nuevo aparece en el siguiente arranque.

**Vídeo congelado con Dolby Atmos** (pantalla negra y el tiempo no avanza): el *audio offload* está desactivado por
defecto (Ajustes → Personalización → Inicio → **Audio offload**).

**Pantalla negra con Dolby Vision**: si un archivo Dolby Vision (por ejemplo WEB-DL "DV HDR10+") se queda en negro,
activa Ajustes → Personalización → Inicio → **Reproducir Dolby Vision como HDR10**. Los archivos Dolby Vision
perfil 8 llevan una capa HDR10, que se reproduce con el decodificador HEVC normal.

## Activar / desactivar

Ajustes (icono de engranaje) → Personalización → Inicio → **Pantalla de inicio moderna** (activada por defecto).
Al cambiarlo, vuelve a abrir el inicio (o reinicia la app). El inicio clásico sigue disponible.

## Clave de TMDB (opcional, para completar "Trending this week")

1. Crea una cuenta gratis en themoviedb.org → Ajustes → API → copia la *API Key* (v3) o el *API Read Access Token* (v4).
2. Añádela a `local.properties` en la raíz del proyecto (este archivo no se sube a Git):

   ```properties
   tmdb.api.key=TU_CLAVE
   ```

3. Vuelve a compilar.

## Compilar e instalar en el Fire TV Stick 4K Max

El ordenador y el Fire TV tienen que estar en la misma red (Wi-Fi o cable).

1. **En el Fire TV**: Ajustes → Mi Fire TV → **Opciones para desarrolladores** → activa **Depuración ADB**.
   (Si no ves ese menú: Ajustes → Mi Fire TV → Acerca de → pulsa OK 7 veces sobre el nombre del dispositivo.)
2. **IP del Fire TV**: Ajustes → Mi Fire TV → Acerca de → **Red** (apunta la dirección, p. ej. `192.168.1.50`).
3. **Conectar desde Android Studio**: abre la pestaña *Terminal* (abajo) y ejecuta (PowerShell en Windows):

   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" connect 192.168.1.50:5555
   ```

   En la tele aparece *¿Permitir depuración USB?*: marca **Permitir siempre desde este ordenador** → Aceptar.
   Si la primera vez sale `failed to authenticate`, acepta el aviso y repite el comando. Debe responder
   `connected to 192.168.1.50:5555`.
4. Arriba en Android Studio, en el selector de dispositivos, elige el Fire TV (aparece como *Amazon AFT…*).
   Variante `debug` (View → Tool Windows → Build Variants) y pulsa **Run ▶**. Se instala y se abre sola.
   La versión debug se llama "Jellyfin Debug" y convive con la oficial, así no pierdes nada.
5. Para volver a abrirla: Inicio del Fire TV → Tus apps → *Jellyfin Debug*.
6. Si algo falla, abre **Logcat** (abajo en Android Studio), elige el Fire TV y filtra por `org.jellyfin`.

Problemas habituales:

- `unable to connect` / `connection refused`: comprueba la IP (cambia si el router la reasigna), que ADB siga
  activado y que no haya una VPN en el PC. Prueba `adb kill-server` y vuelve a conectar.
- El Fire TV desaparece del selector tras reiniciarlo: repite el `adb connect`.
- Para desinstalar la versión debug: `adb uninstall org.jellyfin.androidtv.debug`.

## Controles

- **Izquierda** sobre *Play* / **derecha** sobre *More info*: cambiar de diapositiva del carrusel.
- **OK** en un póster: abre la ficha. Al enfocarlo se ven el título, el episodio y la valoración.
- **Botón ⏯ del mando** sobre un póster: reproduce directamente el episodio que se muestra (o la película).
- **OK** en "Continue watching": reproduce directamente. **Mantener OK**: menú con *Marcar como visto*,
  *Ir a la temporada* e *Ir a la serie* (en películas, *Más información*).
- En una temporada: el menú *Temporada 1 | Temporada 2…* cambia de temporada sin salir de la página. Bajo el
  episodio enfocado, **abajo** lleva al botón ✓ para marcarlo como visto. **Mantener OK** sobre un episodio
  muestra la información del archivo (ubicación, tamaño, códecs, pistas…).
- **Atrás** con la página desplazada: vuelve arriba (no sale de la app).

## Personalizar el aspecto

Todo está en `app/src/main/java/org/jellyfin/androidtv/ui/home/modern/`:

| Archivo | Qué contiene |
|---|---|
| `ModernHomeTheme.kt` | Colores, tamaños de tarjetas, escala de foco, fuente |
| `ModernHomeScreen.kt` | Estructura de la pantalla, fondo difuminado, scroll y foco |
| `HeroCarousel.kt` | Carrusel principal |
| `HomeCards.kt` / `HomeBadges.kt` | Tarjetas y etiquetas |
| `ModernTopBar.kt` | Barra de navegación |
| `GlassSurface.kt` | Efecto cristal de la barra (dibuja el fondo difuminado del banner detrás) |
| `ModernHomeRepository.kt` | Qué se carga en cada fila |
| `MediaBadges.kt` | Reglas de calidad, idiomas y de "New episode/season" (con tests en `app/src/test`) |
| `LanguageFlags.kt` | Banderas de idioma (dibujadas con código, sin imágenes) |
| `../../details/modern/` | Fichas de películas/series/temporadas/personas y `ThemeMusicPlayer.kt` |
| `../../playback/modern/` | Controles del reproductor, menús, miniaturas y desfase de subtítulos |
| `../../music/modern/` | Página de música: media bar, radios y recomendaciones |

La fuente de los títulos es Bebas Neue (licencia SIL OFL, ver `docs/fonts/BebasNeue-OFL.txt`).
