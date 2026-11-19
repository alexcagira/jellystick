# Pantalla de inicio moderna (Fire TV / Android TV)

Inicio estilo plataforma de streaming para el cliente Android TV de Jellyfin: barra de navegación en
píldora, carrusel principal, filas con números de tendencia y etiquetas de **New episode / New season**,
calidad (Dolby Vision, Atmos, HDR, 4K…) y fecha de estreno.

## Qué incluye

| Fila | Origen de los datos |
|---|---|
| Carrusel principal | Películas y series aleatorias con fondo (prioriza las que tienen logo) |
| Continue Watching | Episodios/películas a medias y siguientes episodios mezclados, ordenados por lo último que viste (para el siguiente episodio cuenta la última vez que viste la serie). Al salir de un vídeo se pide de nuevo **en cuanto el servidor tiene la posición** (antes devolvía la anterior), solo esa fila y sin esperar a las demás; lo mismo en las páginas de películas y series |
| Watchlist | Películas y series marcadas con el botón de watchlist (ver más abajo) |
| Trending this week | Tus títulos que son **tendencia esta semana en TMDB** (con clave de TMDB) y después los que más **tendencia tienen en IMDb** (ver *Tendencias de la semana* más abajo). Los huecos se rellenan con lo más visto en tu servidor en los últimos 7 días (todos los usuarios si eres administrador) y lo mejor valorado reciente y de siempre, hasta **10 títulos distintos**: los de bibliotecas ocultas y las copias del mismo título en otra biblioteca (una por idioma) no ocupan sitio |
| Latest Episodes | Episodios por fecha de emisión, hasta hoy (una tarjeta por serie, con la fecha); no por fecha de añadido |
| Latest Films | Películas por fecha de estreno, con la fecha |
| Recently added movies / shows | Por fecha de añadido al servidor |
| Favourites | Tus favoritos |

Reglas de las etiquetas rojas: episodios **emitidos** en los últimos 14 días (`MediaBadges.NEW_CONTENT_WINDOW_DAYS`),
por su fecha de emisión (`PremiereDate`), no por cuándo se añadieron. Una temporada antigua que acabas de
añadir no lleva etiqueta:

- El episodio 1 de la temporada 1 salió hace menos de 14 días → **New series**
- El episodio 1 de otra temporada → **New season**
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
- En **Seguir viendo** (inicio y bibliotecas) las tarjetas 16:9 llevan la calidad, HDR y Dolby Vision y las
  banderas arriba a la derecha. En las que están a medias el tiempo restante ocupa la esquina de arriba a la
  izquierda, así que la calidad va abajo a la izquierda, encima de la barra de progreso.

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
- **Mantén pulsado OK** → *Explorar*: todos los títulos de la biblioteca en cuadrícula (ver abajo).
- **Mantén pulsado OK** → *Escanear biblioteca* (solo administradores): el servidor busca contenido nuevo,
  cambiado o borrado en esa biblioteca, como "Escanear biblioteca" en la web.

## Bibliotecas de películas y series

Al abrir una biblioteca de películas o de series (Films, Películas, TV Shows, Series...) se ve una página como
el inicio:

- **Media bar** con títulos de esa biblioteca (el fondo difuminado es siempre la diapositiva actual).
- Debajo, **pills** con las demás bibliotecas del mismo tipo (para saltar de Films a Películas), **Colecciones**
  y *Explorar*. Al volver de una de esas páginas, el cursor vuelve a su pill. Las **Colecciones** de una
  biblioteca de series solo muestran colecciones con series, y las de películas dejan fuera las que solo tienen
  series (ver *Colecciones de series* más abajo).
- **Seguir viendo** solo de esa biblioteca: películas a medias, o episodios a medias y siguientes episodios.
- **Trending this week** de esa biblioteca, del 1 al 10: la lista de películas o de series en tendencia de TMDB y después la tendencia de IMDb (como en el inicio, pero solo con sus títulos).
- **Últimas películas** (estrenos más recientes de la biblioteca) o **Últimos episodios** en series (por fecha de
  emisión, hasta hoy, uno por serie), como tarjetas
  16:9 con la calidad (4K, HDR, Dolby Vision...) arriba a la izquierda y las banderas de idioma arriba a la derecha.
- **Añadido recientemente**.
- Todos los pósters llevan las etiquetas de calidad, HDR, Dolby Vision y las banderas de idioma del audio, también
  las series (se sacan de uno de sus episodios: aparecen un momento después de abrir la página la primera vez).
  En series salen además *Nuevo episodio*, *Nueva temporada* y *Nueva serie*, como en el inicio.
- Recomendaciones: *Porque viste…*, *Porque te gustó…* (favoritos), *Porque … está en tu Watchlist*.
- **IMDb Top 250** (películas), **Colecciones** y una colección concreta con sus películas.
- *Lo mejor de {estudio}* / *Series de {cadena}*, dos o tres **géneros** con títulos propios
  (*Películas para llorar*, *Películas de acción que merecen la pena*…) y una **etiqueta** popular.
- **Filas de temporada**: en octubre *Películas de Halloween*, terror y thrillers (*Episodios de Halloween* en
  series); del 1 de diciembre al 6 de enero Navidad; del 1 al 14 de febrero San Valentín; en julio y agosto
  *Taquillazos de verano*.
- Las filas con más títulos acaban en una tarjeta **Ver todo** que abre la cuadrícula filtrada.
- Mantener OK en un póster: el mismo menú que en el inicio (reproducir, visto/no visto, identificar…).

La página carga por partes para salir enseguida: primero *Seguir viendo*, *Últimas películas/episodios* y
*Añadido recientemente* (solas, sin el resto de peticiones delante), después la media bar (su sitio queda
reservado y el cursor espera en las pills; cuando llega pasa a *Reproducir* si no has tocado nada) y por último
las demás filas, de tres en tres, y las etiquetas de las series. Además se guarda en el dispositivo lo que
mostró la última vez, así que al abrirla tras reiniciar la app sale al momento y se actualiza por detrás (con
una media bar nueva). Si una fila cambia mientras estás en ella, se queda como estaba hasta que salgas.

### Explorar (cuadrícula)

Todos los títulos en una cuadrícula con título y año, cargados por páginas (sirve para miles de títulos):

- Arriba: **Todo / Sin ver / Vistas / Favoritos**, **Géneros**, **Edad** (solo las clasificaciones de España:
  TP/A, 7, 12, 16, 18...), **Ordenar por** (nombre, aleatorio, fecha de adición, estreno, valoración) y **Al azar**
  (reproduce uno). Las listas de géneros y edades hacen scroll con el cursor.
- **Audio** (bibliotecas de películas y series): los idiomas de audio de la biblioteca con su número de títulos;
  elige uno y solo quedan los títulos con esa pista (se combina con los demás filtros y el orden). Jellyfin no
  filtra por idioma de audio, así que la app lo lee: las pistas de cada película y de los primeros episodios de
  cada serie. Se guarda en el dispositivo (`audio_languages_<biblioteca>.json`) y luego solo lee los títulos
  nuevos (y cada mes otra vez los antiguos, por si cambió el archivo). La primera vez el menú muestra cuántos
  lleva; al abrir la página de la biblioteca se empieza a leer sin prisa. Código: `AudioLanguageIndex.kt`.
- Debajo, las **etiquetas** más usadas: Abajo desde los filtros llega a ellas; OK filtra, otra vez OK la quita.
- Al volver arriba (a las etiquetas o los filtros) la página sube del todo.
- A la izquierda, **# y A-Z**: OK salta al primer título con esa letra (ordena por nombre si hacía falta).
  La letra del título enfocado se ilumina.
- Al volver de una película, el cursor sigue en el póster en el que estabas.
- Los pósters llevan las mismas etiquetas que en el inicio: calidad, HDR, Dolby Vision, banderas de idioma y, en
  series, *Nuevo episodio / temporada / serie*.
- **Géneros sin repetir**: si el servidor tiene el mismo género en inglés y en español ("Action" y "Acción", por
  bibliotecas con metadatos en cada idioma) sale una sola vez, con el nombre en el idioma de la app, y filtra
  por los dos.

## IMDb Top 250

La app crea una colección **IMDb Top 250** con las películas de tu servidor que están en el Top 250 de IMDb y
la actualiza **una vez a la semana** (añade las que entran y quita las que salen). La lista viene de una lista
pública de MDBList que replica el ranking (IMDb no tiene API pública) y las películas se reconocen por su ID de
IMDb. Hace falta permiso para gestionar colecciones; la portada (estrella dorada) solo se sube con una cuenta
de administrador. En la biblioteca de películas aparece como fila, en el orden del ranking.

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

## Barra de navegación

Al subir a la barra flotante, el cursor va directamente a la página en la que estás: *Home* en el inicio, la
biblioteca abierta (Films, TV Shows, Music...) y la Watchlist en su página. Desde la burbuja de música, Arriba
sigue llevando al botón de encima.

En la página de **Música**, al volver de Géneros, Radios, Álbumes... el cursor vuelve a ese acceso.

## Música sonando: burbuja en la barra

Mientras suena música, debajo de los botones de Watchlist, ajustes y usuario aparece una burbuja con la
portada (con un anillo de progreso), la canción y el artista. Abajo desde esos botones llega a la burbuja
y OK abre *Reproduciendo ahora*. Sale en el inicio, las fichas, la Watchlist, la página de Música y las
páginas de álbumes, listas, artistas y géneros (que también tienen la barra de navegación arriba).

## Fluidez

- El paso entre filas es una animación de 320 ms con aceleración suave (la distancia se calcula antes de
  empezar, en vez de ir midiendo mientras se mueve).
- El fondo difuminado cambia sin volver a dibujar la pantalla entera.
- La etiqueta de resolución (4K, 1080p...) se dibuja una sola vez y se reutiliza en todos los pósters.
- El fondo difuminado del inicio es la diapositiva actual de la media bar (no cambia con cada póster).
- La media bar sigue pasando de diapositiva aunque estés más abajo en las filas (inicio, bibliotecas y Música;
  en Música se detiene mientras la tienes enfocada).
- Las imágenes de la media bar (fondo y logo) se piden más ligeras (logo en WebP) y se cargan todas al
  principio, así cada diapositiva aparece al momento.
- Las filas, las pills y los accesos rápidos recuerdan su último elemento con el foco para cuando vuelves con
  las flechas, pero sin `focusRestorer()` de Compose: ese guarda el elemento con la página y desviaba al
  anterior cualquier intento de devolver el foco al volver (en Música siempre acababa en *Géneros*). Está en
  `FocusMemory.kt`.
- **El reproductor primero**: al darle a reproducir, lo que la app pide por detrás (etiquetas de cada serie, qué
  tiene cada colección, actualizar colecciones…) espera unos segundos y mientras se ve el vídeo, y nunca usa más de
  cuatro conexiones. Antes las peticiones del reproductor esperaban detrás de decenas de esas y tardaba en abrir
  (`BackgroundTraffic.kt`).
- La página de Música se abre al instante con lo que mostró la última vez (se guarda en el dispositivo) y
  se actualiza por detrás: primero las filas rápidas, luego la media bar, radios y recomendaciones.
- **Usa la versión release** para el día a día: la debug (la que instala *Run ▶*) se puede depurar y por eso
  Android no optimiza su código; en Compose eso se nota mucho al moverse por las filas. La release va
  optimizada con R8 y lleva un *baseline profile* (`app/src/main/baseline-prof.txt`): las pantallas nuevas y
  Compose se compilan a código nativo al instalarla, así que va fluida desde el primer momento.
- Tras instalar una release nueva con el PC conectado, puedes forzar esa compilación al momento (si no, el
  Fire TV la hace solo cuando está en reposo):

  ```powershell
  & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell cmd package compile -m speed-profile -f com.jellystick.tv
  ```

## Biblioteca de música

Al abrir una biblioteca de música desde la barra se muestra una página con el mismo estilo que el inicio:

- **Media bar** con álbumes recomendados ("Porque escuchas a …"): Reproducir, **Radio** (mezcla
  instantánea a partir del álbum) e Ir al álbum. Izquierda/derecha en los botones de los extremos cambian
  de recomendación; pasan solas cada 10 segundos cuando el foco no está en la barra.
- Accesos rápidos: Álbumes, Artistas del álbum, Artistas, Géneros, Radios, Aleatorio, Cola compartida y
  búsqueda. Al volver de cualquiera de ellos el cursor vuelve a ese mismo acceso.
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

### Álbumes, listas, artistas y géneros

Al abrir un álbum, una lista de reproducción, un artista o un género (desde la página de Música, la búsqueda,
"Ir al álbum/artista"...) se muestra una página con el mismo estilo:

- Portada grande, tipo, nombre, artista, año, número de canciones y duración, sobre la portada difuminada.
- Botones: Reproducir, Aleatorio, Radio (mezcla instantánea), Favorito e Ir al artista (álbumes).
- **Las canciones**: OK reproduce desde esa canción, el botón play/pausa también. Mantener OK:
  Reproducir desde aquí, Añadir a la cola, Añadir a una lista, Favoritos, Ir al álbum, Ir al artista.
- **Corazón** a la derecha de cada canción (Derecha desde la canción; arriba/abajo pasa de corazón en corazón):
  la marca como favorita (rojo) o la quita (contorno).
- Al volver arriba a los botones desde las canciones o los álbumes, la página sube del todo.
- Artistas: sus álbumes en cuadrícula y sus canciones más escuchadas. Géneros: sus álbumes y la radio del género.
- Álbumes: "More like this" con álbumes parecidos.

**Favourite Tracks**: las canciones favoritas (desde el corazón, el menú de una canción o *Reproduciendo
ahora*) se añaden a una lista de reproducción llamada *Favourite Tracks*, y se quitan al desmarcarlas. La
lista se crea con la primera favorita (con todas las que ya tenías) y lleva una portada propia: un corazón
verde doble como el logo de Jellystick (subirla requiere ser administrador). Al abrir Música se comprueba
una vez por sesión, para incluir las favoritas marcadas desde otras apps.

En la barra de accesos de Música: Álbumes, Artistas del álbum, Artistas, **Géneros** (cuadrícula de géneros
como cuadrados de color con su nombre), **Radios** (todas: para ti, una por género y una por artista), Aleatorio, Cola compartida y
la **lupa** para buscar solo música.

### Reproduciendo ahora

- Arriba, pequeños: "Reproduciendo", el artista y la hora.
- Portada y canción, álbum y artista. La letra está **oculta por defecto**; con el botón de letra se muestra
  más grande (y la portada crece un poco). Se recuerda mientras la app está abierta.
- Botones bajo la barra de tiempo: Anterior, Reproducir/Pausa, Siguiente, **Detener** (vacía la cola y sale de
  la pantalla), **Favorito** (solo el corazón, rojo si ya lo es), Añadir a una lista, Aleatorio, Letra, Ir al artista, Ir al álbum y **Salvapantallas**.
  El nombre del botón aparece al enfocarlo.
- La pantalla no tiene scroll: la portada se ajusta al espacio que queda, así que siempre se ve entera.
- **⏪ / ⏩ del mando**: canción anterior / siguiente (también en el salvapantallas, sin salir de él).
- La portada, el fondo y la letra de la siguiente canción se cargan mientras suena la actual, así el cambio
  es inmediato (la portada anterior se queda hasta que la nueva está lista).
- **Salvapantallas**: portada, canción, álbum y artista sobre la portada difuminada; con la letra activada,
  la letra grande al lado, sin moverse. **OK** muestra u oculta la
  letra (botón discreto abajo a la derecha), play/pausa pausa, y cualquier otro botón lo cierra.
- **Next up**: en los últimos 10 segundos de cada canción, arriba a la izquierda del salvapantallas (el de
  Reproduciendo ahora y el de la app) sale una tarjeta compacta con la portada, el nombre y el álbum de la
  siguiente (`NextUpNotice.kt`). En el de Reproduciendo ahora la portada y la letra bajan un poco mientras
  se ve, para que no la tapen. No sale si la cola acaba con esa canción ni en canciones de menos de 30 s.
- **Cola**: portadas con canción y artista · álbum debajo, y la posición ("3 / 24"). OK salta a esa canción;
  mantener OK: Reproducir desde aquí, Quitar de la cola, Favoritos, Ir al álbum, Ir al artista. Las canciones
  **ya escuchadas** siguen a la izquierda, más tenues, para volver a ponerlas (la fila empieza en la actual).
- **☰ del mando (en cualquier pantalla menos el reproductor de vídeo)**: lo mismo que *Detener*: para la música, vacía la cola (también las
  canciones ya escuchadas) y cierra la cola compartida. Solo cuando hay música; si no, el botón hace lo de
  siempre (`ui/music/MusicStopper.kt`).

### Cola compartida (como una "fiesta")

En la página de Música → **Cola compartida** → *Empezar a compartir*. La tele muestra un código QR:
cualquiera en el mismo Wi-Fi lo escanea con la cámara del móvil, busca canciones o álbumes y los añade
al final de la cola. No necesitan app ni cuenta de Jellyfin; la web la sirve la propia tele
(puerto 8765) y usa tu sesión.

- Solo suena música de tu biblioteca: la web ya no tiene la casilla para pegar enlaces de Spotify, Apple
  Music o YouTube Music (la tele aún entiende `/api/link`, pero nada lo usa).
- Si no sonaba nada, empieza a sonar lo que añadan; si tenías la música en pausa, sigue en pausa.
- Si la página no abre: el móvil tiene que estar en el mismo Wi-Fi que la tele (no en la red de invitados,
  que suele aislar los dispositivos). La dirección usa ahora la IP de la red activa de la tele; antes podía
  salir la de Wi-Fi Direct (192.168.49.1), que los móviles no alcanzan.
- La web muestra lo que suena y lo que han añadido los invitados (con su nombre).
- **Si la música se paraba tras 5 o 6 canciones**: la cola calculaba mal su tamaño cuando antes se habían
  quitado canciones de otra cola (y en aleatorio podía elegir una canción que no existía), así que daba la
  cola por terminada. Ya está corregido; además, si la cola se acaba y añaden canciones, sigue con las
  nuevas (antes volvía a la primera), *Play* recupera la reproducción tras un error de red, y el Fire TV
  no entra en reposo mientras suena música aunque nadie toque el mando. La música tampoco usa ya el
  *audio offload* salvo que lo actives en los ajustes del reproductor (en Fire TV puede congelar el sonido).
- **Cuando la cola se acaba** se guarda todo lo que sonó: si alguien añade otra canción empieza por ella (p. ej.
  la sexta de la cola) y las anteriores siguen en la cola de *Reproduciendo ahora* para volver a ponerlas. Solo
  se borran con **Detener** o con **☰** del mando, que además cierran la cola compartida (luego se empieza otra).
- El enlace lleva un código que cambia cada vez que empiezas a compartir. *Dejar de compartir* lo corta.
- La página es `app/src/main/res/raw/shared_queue.html`.

## Búsqueda

La lupa de la barra abre la búsqueda nueva: películas, series, episodios, colecciones, personas y música, con
los pósters con todas las etiquetas (4K/1080p, Dolby Vision, HDR, Atmos y banderas de idioma).

- **Por voz**: pulsa OK en el cuadro de búsqueda para abrir el teclado y, con el teclado abierto, **mantén
  pulsado el botón del micrófono** del mando del Fire TV y di lo que buscas. Si el dispositivo tiene
  reconocimiento de voz de Android, sale además un botón de micrófono al lado.
- El botón del micrófono con el teclado cerrado abre Alexa (eso lo controla el sistema, no la app).
- **Sin texto**: *Explorar por género* (tiles de colores con icono) y *Explorar por etiqueta* (pills).
  OK abre la cuadrícula de ese género o etiqueta con películas y series. Los géneros se guardan en el
  dispositivo y se cargan por detrás al abrir el inicio, así salen al momento.
- En la **búsqueda de Música** (la lupa de la página de Música), sin texto salen los géneros de música como
  tiles (rock, pop, hip hop, electrónica, jazz...): OK abre la página del género con sus álbumes y su radio.
- **Con texto**: resultados en filas como el inicio: Películas, Series, Colecciones, Episodios (tarjetas
  16:9) y Personas, y después la música.

## Temporadas: mantener OK

En la página de una serie, mantener OK sobre una temporada abre: Reproducir ahora (el primer episodio sin ver
de la temporada), Marcar como vista y Marcar como no vista.

## Menú de los pósters (mantener OK)

Mantén OK sobre cualquier póster del inicio o de la Watchlist:

- **Películas**: Reproducir ahora, Marcar como visto, Marcar como no visto, **Añadir a / Quitar de la
  watchlist**, **Más información** (su ficha, desde cualquier fila), Identificar, Información del archivo.
- **Series**: Reproducir ahora, Marcar como visto, Marcar como no visto, Añadir a / Quitar de la watchlist, Ir a
  la serie, Identificar.
- **Episodios** (Continue Watching, Latest Episodes): además Ir a la temporada e Información del archivo;
  la watchlist e Identificar actúan sobre la serie.
- El botón de la watchlist pregunta al servidor al abrir el menú si ya está en ella (algunas filas llegan sin
  esa información) y cambia su texto; es la misma watchlist que la de la ficha y la de KefinTweaks en la web.

**Identificar** funciona como en la web: busca en los proveedores de metadatos del servidor (TMDb, TVDb...)
con el nombre y el año, o con el ID de IMDb (`tt0120737`) o de TMDb. Al elegir el resultado se aplican
los metadatos y las imágenes nuevas (tardan unos segundos). Solo aparece para administradores.
Los campos de texto (nombre, año, ID) abren el teclado al pulsar OK sobre ellos; la tecla de buscar del
teclado vuelve a buscar, y Atrás deja de escribir sin cerrar el diálogo.

## Colecciones por idioma de audio

En las bibliotecas de **Películas** y **Series**, el botón **Filtro** (icono de embudo, junto a ordenar) de la
cuadrícula clásica, o **＋ Por idioma** en la página **Colecciones** de la biblioteca, lee los idiomas de audio de
todos los títulos y muestra cuántos hay de cada uno (con su bandera).
Al elegir un idioma:

1. Nombre de la colección (por defecto "Películas en Inglés", "Series en Español"...).
2. Póster: **Bandera** (generado por la app con la bandera y el idioma) o el póster de cualquiera de los
   títulos de la colección.
3. *Crear colección*. Es una colección normal de Jellyfin: aparece en la pestaña **Colecciones** y en
   todas las apps.

Las series cuentan con el idioma de sus primeros episodios. La app recuerda cómo se hizo cada colección
y una vez al día (al abrir el inicio) añade los títulos nuevos y quita los que ya no tienen ese idioma.
También se puede actualizar a mano: vuelve a Filtro y elige el idioma (sale "Colección creada" →
*Actualizar colección*, y puedes cambiar el póster).

Permisos: crear colecciones necesita que el usuario pueda gestionar colecciones; cambiar el póster
necesita un administrador (si no, la colección se crea con el póster automático de Jellyfin).

## Colecciones de series

En una biblioteca de series (TV Shows) → pill **Colecciones** se ven solo las colecciones que tienen series; en
la de películas, las que solo tienen series no salen (ni en la pill ni en las filas *Colecciones* de la página).
Jellyfin no dice qué tiene cada colección, así que la app mira cada una una vez (una petición pequeña, por detrás
al abrir el inicio) y lo guarda en el dispositivo hasta que cambia su número de títulos.

Arriba, junto a ordenar:

- **＋ Por plataforma**: agrupa las series por dónde se estrenan, con la cadena de sus metadatos (Jellyfin la
  guarda como *estudio* de la serie al descargar los metadatos de TMDb o TVDb). Primero las **plataformas de
  streaming** (Netflix, Prime Video, Disney+, HBO Max, Apple TV+, Movistar Plus+, SkyShowtime, Atresplayer,
  Filmin, Crunchyroll…; "HBO", "HBO Max" y "Max" cuentan como una, igual "Amazon" y "Prime Video") y después
  las **cadenas de TV** con al menos dos series (AMC, BBC One, FX, Antena 3…). Al elegir una: nombre ("Series
  de Netflix"), póster (**Generado**: una pantalla con el nombre de la plataforma, sin logotipos, o el póster de
  una de sus series) y *Crear colección*.
- **＋ Por idioma**: las colecciones por idioma de audio de arriba, con las series de la biblioteca.

Se actualizan solas una vez al día (añaden las series nuevas y quitan las que ya no son de esa plataforma) y
también a mano, eligiendo otra vez la plataforma (*Actualizar colección*). Si una serie no sale en ninguna
plataforma, le falta la cadena en los metadatos: *Actualizar metadatos* en el servidor la añade. El código
está en `ui/collections/` (`ServiceCollectionsRepository.kt`, `StreamingServices.kt`, `CollectionCatalog.kt`).

## Valoraciones: IMDb y Letterboxd

- Las **estrellas** (pósters, media bar, fichas, episodios y pantalla de pausa) son la nota de **IMDb**. Salen del
  conjunto de datos oficial de IMDb `title.ratings.tsv.gz` (https://developer.imdb.com/non-commercial-datasets/,
  uso personal y no comercial, sin clave), que la app descarga 1 minuto después de abrirse la primera vez
  (unos 8 MB) y después una vez al día. Se guarda en el dispositivo (`imdb_ratings.bin`, unos 8 MB) y se busca
  por el id de IMDb de cada título (`ProviderIds.Imdb`). Los títulos sin id de IMDb, o hasta que termina la primera
  descarga, siguen con la nota del servidor (la de TMDb con los proveedores por defecto). En la ficha, junto a la
  nota pone **IMDb** cuando es la de IMDb (`ImdbRatings.kt`).
- En la ficha de una **película** sale además la nota media de **Letterboxd** (de 0 a 5). Letterboxd no tiene API
  pública: la app abre la página de la película (`letterboxd.com/imdb/tt…/`, o por el id de TMDb) y lee la nota de
  su etiqueta `twitter:data2`, como las extensiones del navegador. Se guarda una semana (un día si no tiene nota).
  Si Letterboxd cambia su página o bloquea la petición, simplemente no sale (`LetterboxdRatings.kt`).

## Cola de episodios (series largas)

Al reproducir un episodio, la app pide al servidor ese episodio y los siguientes
(`Shows/{serie}/Episodes?startItemId=`). El servidor arma esa lista con las temporadas y la "clave de serie" que
guarda cada episodio: si un episodio tiene una clave antigua (la serie se identificó de nuevo, se actualizó el
servidor…) o un número de temporada que no cuadra, se queda fuera, y si era el elegido la lista llega vacía y **no
pasaba nada** al darle a reproducir (South Park desde *Continue Watching* o desde la ficha). Ahora
(`EpisodeQueue.kt`):

- Si la lista no empieza por el episodio elegido, falla o tarda más de 12 s, los episodios se leen de la carpeta de
  la serie, ordenados por temporada y episodio (primero solo el orden, sin la información de los archivos; luego los
  que se van a reproducir con todo).
- Si tampoco aparece, el episodio se reproduce solo. Reproducir una serie o una temporada usa el mismo respaldo.
- Si aun así no se puede cargar, sale un aviso en vez de no hacer nada.

En el servidor suele arreglarse con *Actualizar metadatos → Reemplazar todos los metadatos* de la serie (o un
escaneo completo de la biblioteca), que vuelve a guardar la clave en cada episodio.

## Tendencias de la semana (Trending this week)

Orden de cada fila (`ModernHomeRepository.trendingTitles`), un título por película o serie:

1. **TMDB** (con clave): la lista oficial de tendencias de la semana de TMDB (`/trending/{all|movie|tv}/week`,
   60 títulos en el inicio, 100 de películas o de series en cada biblioteca), en su orden, con los títulos que
   tienes (por su id de TMDB). Se pide cada 6 horas.
2. **IMDb**: la tendencia calculada con los votos de IMDb (abajo).
3. Los más votados en IMDb de los estrenados en el último año.

En el inicio, si aún faltan, lo más visto en tu servidor y lo mejor valorado.

**Clave de TMDB**: Ajustes → Personalización → Inicio → **Clave de TMDB (Trending this week)**. Es gratis: cuenta en
themoviedb.org → Ajustes → API → *Clave de API* (v3) o *Token de acceso de lectura* (v4). Se escribe en la tele (con la
app Fire TV del móvil se puede pegar), la app la comprueba con TMDB antes de guardarla y se puede borrar. Cada
persona usa la suya: no va dentro del APK. Si compilas con `tmdb.api.key` en `local.properties`, esa se usa cuando
no hay ninguna en los ajustes. *This product uses the TMDB API but is not endorsed or certified by TMDB.*

### Tendencia de IMDb

Las listas de popularidad de IMDb (MOVIEmeter, TVmeter) no se pueden leer desde una app: no hay API gratuita y
IMDb no permite leer sus páginas de forma automática. El archivo diario de valoraciones sí (uso personal), y trae
el **número de votos** de cada título. Con cada descarga diaria (`ImdbRatings`) la app guarda los votos de los
títulos con al menos 1.000 (`files/imdb_votes/votes-<día>.bin`, ~1 MB por día, se quedan los últimos 10 días) y
compara el día más nuevo con el de hace una semana (`ImdbTrending.kt`):

    tendencia = votos ganados − votos de hace una semana × crecimiento habitual

El crecimiento habitual es la mediana de los títulos con más de 10.000 votos: un clásico que gana sus votos de
siempre queda en 0, y un estreno o una serie con temporada nueva sube mucho. Un título que hace una semana tenía
menos de 1.000 votos cuenta desde 0. Hacen falta al menos 100 votos de más para contar.

- Con un solo día de votos (los primeros días tras instalar), y para los huecos, van los títulos más votados en
  IMDb de los estrenados en el último año.
- Se ordenan los títulos de tus bibliotecas que tienen id de IMDb (los del inicio: películas y series de todas;
  en cada biblioteca, solo las suyas), uno por título.
- El archivo de IMDb se mira cada 3 horas y se descarga si el del dispositivo tiene más de 20 horas.

## Títulos nuevos en el servidor

El inicio, las páginas de bibliotecas y las fichas se recargan solos cuando el servidor avisa de cambios en la
biblioteca (`DataRefreshService.libraryChanged`, avisos en `MainActivity`):

- `LibraryChanged`: el servidor lo manda después de esperar a que no haya más cambios, **30 s** por defecto
  (`LibraryUpdateDuration`). Antes, además, la vigilancia en tiempo real espera **60 s** desde que aparece un
  archivo nuevo antes de escanearlo (`LibraryMonitorDelay`).
- Si tu usuario es administrador, la app también recibe el fin de cada actualización de un título
  (`RefreshProgress` al 100 %) y recarga en ese momento, sin esperar a `LibraryChanged`.

Para que lleguen antes, baja esos valores en el `system.xml` del servidor (en Docker, `/config/config/system.xml`
o `/config/system.xml`) con Jellyfin parado, y arráncalo otra vez:

```xml
<LibraryMonitorDelay>10</LibraryMonitorDelay>
<LibraryUpdateDuration>5</LibraryUpdateDuration>
```

## Fichas de películas, series, temporadas y personas

"More info" (y cualquier ficha de la app) abre la nueva página para películas, series, temporadas y
personas: fondo a pantalla completa con logo, valoración, duración/temporadas, géneros, sinopsis y
botones (*Play*, favoritos, tráiler, visto, información del archivo, watchlist y ⋮ para la ficha clásica con
todas las opciones). Debajo: *Next up*, temporadas, episodios, reparto y títulos similares.

Encima de los episodios, las **temporadas** van en pastillas; en series con muchas (South Park, Los Simpson) la fila se
desplaza sola para que la temporada actual quede a la vista, al abrir la ficha y al cambiar de temporada, y sigue al
cursor al moverte por ellas, dejando sitio para ver las siguientes (`SeasonTabs`; la página no se desplaza sola con el
foco, `NoAutoScrollBringIntoViewSpec`, así que las pastillas llevan su propio `KeepInViewBringIntoViewSpec`).

En series y temporadas el botón muestra el episodio: **Resume S01:E05** si ya lo empezaste, **Play S02:E01**
si no (en español, *Reanudar T01:E05* / *Reproducir T02:E01*). En películas empezadas hace más de un minuto el
botón dice **Reanudar desde 1:05:32**. En los dos casos, al lado sale **⏮** (antes de *Favoritos*): lo vuelve a
reproducir desde el principio, sin intros (`retrieveAndPlay(..., fromStart = true)`). Al volver del reproductor
el botón cambia al momento, antes de que el servidor tenga la posición; la ficha se recarga cuando la tiene.
El servidor solo guarda la posición a partir del 5 % del título (*Panel de control → Reproducción → Reanudar →
Porcentaje mínimo para reanudar*): para que una película de 2 horas diga *Reanudar desde* al minuto, baja ese
valor a 1. En una temporada los episodios salen en un
carrusel que empieza en el siguiente por ver (marcado como *Next up*). Se reproducen con OK o con ⏯;
manteniendo OK se abre su ficha clásica. Cada episodio lleva su calidad (4K, HDR, Dolby Vision…) arriba a la
izquierda (abajo a la izquierda en el *Next up*) y las banderas de sus pistas de audio arriba a la derecha, junto
al ✓ de visto.

Se puede desactivar en Ajustes → Personalización → Inicio → **Fichas modernas**. Los demás tipos
(episodios, álbumes, colecciones…) siguen usando la ficha clásica.

## Subtítulos

Ajustes → **Subtítulos** (también en Reproducción): una vista previa sobre una imagen con zonas claras y oscuras y:

- **Estilos** de un toque: *Streaming* (blancos con sombra suave y sin caja, como en Netflix), *Clásico* (contorno
  negro), *Negrita* (con sombra), *Caja* (sobre fondo oscuro) y *Amarillo* (como en el cine). El tamaño y la
  posición se mantienen.
- **Personalizar**: contorno y sombra (ninguno, contorno, sombra, relieve, grabado), fuente (predeterminada,
  condensada, con serifa, monoespaciada), tamaño, grosor (negrita), color del texto, del fondo y del contorno o la
  sombra, y la posición desde abajo.

Los subtítulos ASS/SSA con estilos propios se dibujan como vienen en el archivo.

## Reproductor

Controles nuevos para películas y episodios (la TV en directo mantiene los originales, con la guía):

- **Reproduciendo**: sin nada en pantalla. **OK** pausa, **izquierda/derecha** buscan hacia delante o atrás
  (mantener pulsado acelera) con miniatura de la escena, **arriba/abajo** muestran los controles.
- **Buscar en la barra**: el vídeo sigue donde estaba mientras buscas; la barra marca en blanco hasta dónde vas.
  **OK** salta ahí; **Atrás**, salir de la barra o 8 segundos sin tocar nada lo olvidan y todo sigue igual.
- **Subtítulos encima de los controles**: al mostrarse la barra los subtítulos suben lo justo para verse por encima
  y bajan a su sitio cuando se oculta (`VideoManager.raiseSubtitlesAbove`).
- **Miniaturas**: si la versión que se reproduce no tiene imágenes de trickplay se usan las de otra versión del
  mismo archivo, y si el reproductor no trae esa información se pide al servidor.
- **Controles**: título y logo arriba; barra de progreso (OK reproduce/pausa) con los
  capítulos marcados con líneas, tiempo transcurrido y restante; episodio anterior / siguiente y "Termina a las…"
  abajo a la izquierda; abajo a la derecha **audio**, **subtítulos**, **escenas**, **calidad**, **modo de
  reproducción**, **velocidad** y **desfase de subtítulos**. En pausa se ven dos barras discretas en el centro. Se ocultan solos a los 5 segundos; **Atrás** también los oculta.
- **En pausa** (sin controles): "Estás viendo" con el logo, temporada y episodio, año, duración, valoración y
  sinopsis.
- **☰ del mando: información de reproducción**. Abre (y cierra) un panel de cristal translúcido a la derecha, sin
  quitar el foco al vídeo: el mando sigue controlando el reproductor. Se actualiza cada segundo:
  - *Reproducción*: directa, transmisión directa (remux) o transcodificación, el **motivo** (contenedor, códec de
    audio, subtítulos que hay que grabar, límite de calidad…), el *Modo de reproducción* de la app si no es el
    automático y, cuando el servidor convierte, su progreso, fps y aceleración (VAAPI, QSV… o software). El motivo
    sale de la URL de transcodificación y de la sesión del servidor (`/Sessions?deviceId=`), que se pide cada 3 s
    solo si no es reproducción directa.
  - *Archivo*: nombre, contenedor, tamaño y bitrate.
  - *Vídeo* y *Audio*: el original (códec, perfil, bits, HDR / Dolby Vision con su perfil, resolución, fps,
    bitrate, canales, frecuencia), lo que **envía** el servidor si lo convierte y lo que hace el Fire TV: el
    decodificador que abrió (`c2.mtk.hevc.decoder`…), o *passthrough* si el audio va sin decodificar a la tele o
    la barra de sonido, y los fotogramas perdidos.
  - *Subtítulos*: idioma, formato y si van aparte, dentro del vídeo o grabados por el servidor (en naranja: eso
    obliga a transcodificar).
  - *Conexión*: segundos de vídeo ya descargados (búfer) y la calidad máxima elegida.
  En naranja lo que le cuesta trabajo al servidor. Se cierra con ☰ o **Atrás**; mientras se ven los controles se
  aparta y vuelve al ocultarlos. En el reproductor ☰ ya no detiene la música (`StreamInfo.kt`,
  `StreamInfoPanel.kt`, `VideoManager.getPlayerStats`).
- **Saltar intro / Saltar créditos / Saltar resumen**: usa los segmentos del servidor (plugin *Intro Skipper* o
  segmentos de Jellyfin 10.10+) cuando su acción es "Preguntar" en los ajustes de reproducción (segmentos multimedia). OK salta.
- **Desfase de subtítulos**: de ±0,1 s o ±0,5 s, se reinicia en cada reproducción. No afecta a los subtítulos
  ASS/SSA con estilos (los dibuja otra librería).

Se desactiva en Ajustes → Personalización → Inicio → **Controles modernos del reproductor**.

**Archivos que no se reproducen en directo**: a veces el servidor dice *Direct playing* pero el Fire TV no puede con
el archivo (un códec que dice soportar y no decodifica, un audio que la tele o la barra de sonido rechazan...).
La app lo detecta y lo vuelve a pedir con más trabajo del servidor, paso a paso:

1. **Dolby Vision como HDR10** (solo archivos Dolby Vision, cuando no sale imagen o el reproductor da error): el
   Fire TV decodifica la capa HDR10 que llevan los Dolby Vision perfil 8 (los WEB-DL "DV HDR10+"). No necesita al
   servidor.
2. **Convertir el audio**: el vídeo tal cual y el audio convertido, lo mismo que hace Plex con el E-AC3 Atmos de
   *Los anillos de poder* en el Fire TV (cuando hay imagen pero el tiempo no avanza durante 10 s, o tras el paso 1).
3. **Transcodificar**: vídeo y audio convertidos (si lo anterior no basta), a lo que reproduce
   cualquier aparato: H.264 de 8 bits hasta 1080p con audio AAC y como mucho 20 Mbps. Sin ese límite el servidor
   mantenía 4K y el códec que el aparato dice soportar, y el resultado tampoco se veía (ni le daba tiempo a hacerlo).
   Si el servidor rechaza la transcodificación (p. ej. el usuario no tiene permiso) sale un aviso y se cierra el
   reproductor, en vez de quedarse en negro.

El **emulador de Android Studio** ("AOSP TV on x86" en el panel de Jellyfin) no tiene decodificador por hardware:
no puede con HEVC 10 bits en 4K ni con Dolby Vision, así que esos archivos siempre irán transcodificados ahí. En el
Fire TV 4K Max se reproducen en directo.

Sale un aviso en cada intento y sigue en el mismo punto. El paso que funcionó se recuerda para **toda la serie**
(o la película), así los siguientes episodios empiezan ya así. En los controles, el botón **Modo de reproducción**
(⇄) muestra cómo se está reproduciendo ahora (directo, emisión del servidor o transcodificando) y permite elegirlo
a mano (también *Reempaquetar*, el remux solo, y en archivos Dolby Vision el interruptor *Dolby Vision como HDR10*);
*Automático* vuelve a intentar la reproducción directa y olvida lo recordado. Transcodificar necesita que
tu usuario tenga permiso para ello en el servidor y que el servidor pueda hacerlo (CPU o aceleración por hardware).
El código está en `ui/playback/PlaybackFallback.kt`.

**El reproductor primero**: OkHttp manda como mucho 5 peticiones a la vez al mismo servidor y pone las demás
en cola, y el vídeo, la API y las sintonías comparten esas 5: el reproductor esperaba detrás de las filas del
inicio. Ahora son 24 (`MAX_REQUESTS_PER_SERVER` en `di/AppModule.kt`). Al darle a reproducir
(`BackgroundTraffic.playRequested`) el inicio, las páginas de biblioteca y la ficha (si aún cargaba *Más como
esto*) dejan de cargar lo que estaban pidiendo; se vuelve a pedir al volver. Al salir del reproductor, las
peticiones de fondo esperan 10 s para que el aviso de parada y *Continue watching* vayan primero.

**Continue watching al instante**: al parar un vídeo la fila cambia ya, sin esperar al servidor
(`ModernHomeRepository.continueAfterStop`): a medias, el título pasa el primero con su nueva barra; terminado
(desde el 90 %, como el servidor), sale de la fila y, si es un episodio, entra el siguiente de la cola. En las
páginas de Películas y Series solo cambian sus propios títulos. Cuando el servidor confirma la parada se pide la
fila de verdad; hasta entonces no se recargan las filas (habrían salido con la posición antigua).

**Abrir el reproductor más rápido**: al reproducir un episodio solo se piden antes ese y los dos siguientes (con
toda su información); los demás de la cola (hasta 150) se añaden 15 s después, ya reproduciendo. Antes se pedían
los 150 con pistas, capítulos e imágenes de trickplay y, con el servidor ocupado (tareas programadas), el
reproductor tardaba segundos en abrirse (`SdkPlaybackHelper.QUICK_EPISODE_LIMIT`).

**Fichas al momento**: la ficha de una película o serie sale en cuanto llega el título (cabecera, botones y
reparto); las temporadas y el siguiente episodio después, y *Más como esto* lo último, porque es la petición más
lenta (el servidor compara toda la biblioteca) y la ficha la esperaba. *Reproducir* antes de que llegue el
siguiente episodio lo busca en ese momento. Al volver tras reproducir, la ficha se cambia entera al final.

**Arranque rápido**: el inicio guarda lo que mostró la última vez y lo enseña al momento al abrir la app; las
filas nuevas se cargan por detrás y el carrusel nuevo aparece en el siguiente arranque.

**Vídeo congelado con Dolby Atmos** (pantalla negra y el tiempo no avanza): el *audio offload* está desactivado por
defecto (Ajustes → Personalización → Inicio → **Audio offload**).

**Dolby Vision + HDR10+ en el Fire TV (WEB-DL "DV HDR10+", p. ej. *Los anillos de poder*)**: el Fire TV 4K / 4K Max
se queda en negro si el decodificador Dolby Vision recibe también los metadatos HDR10+. Jellyfin para Android TV lo
evita diciéndole al servidor que ese tipo no se soporta (`KnownDefects.hevcDoviHdr10PlusBug`): el servidor quita
Dolby Vision al vuelo (*Re-muxing* en el panel) y la tele recibe HDR10+. Jellystick hace lo que Plex: el archivo se
reproduce **en directo** y, solo cuando lo decodifica el decodificador Dolby Vision, cada mensaje HDR10+ del fotograma
(SEI T.35 de tipo 4 con la firma B5 003C 0001 04) se convierte en uno de tipo 5 que los decodificadores ignoran: un
byte por fotograma, sin copiar nada (`Hdr10PlusSei` en `ui/playback/modern/SubtitleOffset.kt`). La tele recibe
**Dolby Vision** y el audio E-AC3 Atmos va en *passthrough*. El perfil 7 (con capa de mejora) sigue pasando por el
servidor. Si aun así se queda en negro, el vigilante del arranque pasa a *Dolby Vision como HDR10* (que conserva
HDR10+) y lo recuerda para la serie.

Al instalar la 1.3.2 se olvida una vez lo recordado para cada serie (modo de reproducción y Dolby Vision como HDR10):
lo que siga fallando vuelve a aprenderse solo en el primer intento.

**Pantalla negra con Dolby Vision (otros casos)**: si un archivo Dolby Vision se queda en negro, activa Ajustes →
Personalización → Inicio → **Reproducir Dolby Vision como HDR10** (o el interruptor del *Modo de reproducción* solo
para esa serie). Los archivos Dolby Vision perfil 8 llevan una capa HDR10, que se reproduce con el decodificador HEVC
normal. Con ese ajuste activado **todo** el Dolby Vision sale como HDR10.

**Cuadros de fotogramas anteriores con Dolby Vision como HDR10**: cada fotograma Dolby Vision lleva al final sus
metadatos (la RPU, NAL de tipo 62, y en el perfil 7 la capa de mejora, tipo 63). El decodificador HEVC debería
ignorarlos, pero en el Fire TV algunos fotogramas salían con bloques de otros anteriores. Mientras Dolby Vision se
reproduce como HDR10 (el ajuste, el interruptor del reproductor o el paso automático), el renderizador de vídeo
(`DolbyVisionAsHdr10VideoRenderer` en `ui/playback/modern/SubtitleOffset.kt`) los quita antes de dárselos al
decodificador: solo mira el final de cada fotograma y no copia nada.

## Activar / desactivar

Ajustes (icono de engranaje) → Personalización → Inicio → **Pantalla de inicio moderna** (activada por defecto).
Al cambiarlo, vuelve a abrir el inicio (o reinicia la app). El inicio clásico sigue disponible.

## Icono y banner (Jellystick)

**Icono con fondo transparente**: el Fire TV pone las apps instaladas fuera de la Appstore en una burbuja gris
con el icono cuadrado en el centro. Con el fondo azul marino se veía un cuadrado oscuro con barras grises a los
lados; ahora el icono (`mipmap-*/app_icon.png` y el adaptable, con `drawable/app_icon_background.xml`
transparente) es solo el logo, así que la casilla entera es del gris del Fire TV, como las demás apps de fuera
de la Appstore.

La app se llama **Jellystick** (también la versión debug: `app_name_release` y `app_name_debug` en
`values/strings.xml`). El banner que muestra el Fire TV (`mipmap-*/app_banner.png` y
`drawable/app_banner_foreground.xml`) lleva el **rombo** de Jellystick en verde (degradado `#9BE15D` → `#00B37A`) y el
nombre Jellystick, igual que el logo de dentro de la app (`drawable/app_logo.xml`, `drawable-v24/app_logo.xml`,
colores en `values/logo.xml` y `src/debug/res/values/logo.xml`): arranque, barra y salvapantallas.

La casilla 16:9 de la pantalla de inicio es `drawable-*/app_banner_tv.png`: el logo con fondo azul marino
(`#000B25`) en `drawable-xhdpi` (320 × 180), `drawable-xxhdpi` (480 × 270) y `drawable-xxxhdpi` (640 × 360),
PNG y no en `mipmap` ni como icono adaptable. Android TV y Google TV usan `android:banner`; el Fire TV, con las
apps que no vienen de la Appstore de Amazon, necesita **`android:banner` y `android:logo`** tanto en
`<application>` como en `StartupActivity` (la actividad del launcher), y que esa actividad **no tenga icono
propio**: si lo tiene, dibuja ese icono cuadrado centrado sobre gris. La app tiene el icono cuadrado
(`@mipmap/app_icon`, también como `roundIcon`), el que sale en Ajustes.

Si aun así sale el icono cuadrado sobre gris, la casilla completa solo es posible publicando la app en la
Appstore de Amazon (allí se sube la imagen de 1280 × 720).

**El Fire TV guarda la imagen de cada app**: al actualizar encima sigue mostrando la antigua. Para ver la nueva hay
que desinstalar Jellystick e instalarla otra vez (reiniciar o borrar datos no basta).

Si el Fire TV sigue enseñando el banner o el nombre antiguos, desinstala la app y vuelve a instalarla (el
launcher guarda la imagen de las apps ya instaladas); si aun así no cambia, reinicia el Fire TV. El icono
(`mipmap-*/app_icon.png` y `drawable/app_icon_foreground.xml`) es el mismo logo en verde.

**El logo es propio**: un rombo (un poco más alto que ancho) con las esquinas redondeadas, hueco, y otro rombo
pequeño dentro; ya no usa el triángulo de Jellyfin, que es una marca suya. Está en todos los sitios: icono
(normal y monocromo), banner y casillas del Fire TV, logo de arranque, barra y salvapantallas, el icono de
*Acerca de* y de los servidores en Ajustes (`drawable/ic_jellyfin.xml`, mismo nombre de recurso) y las imágenes
de `docs/images`. El texto está dibujado con Montserrat Bold (SIL Open Font License).

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
   La versión debug también se llama "Jellystick" y convive con la oficial, así no pierdes nada. Es la
   versión para probar cambios: es bastante **más lenta** que la release (ver "Publicar el APK").
5. Para volver a abrirla: Inicio del Fire TV → Tus apps → *Jellystick*.
6. Si algo falla, abre **Logcat** (abajo en Android Studio), elige el Fire TV y filtra por `org.jellyfin`.

Problemas habituales:

- `unable to connect` / `connection refused`: comprueba la IP (cambia si el router la reasigna), que ADB siga
  activado y que no haya una VPN en el PC. Prueba `adb kill-server` y vuelve a conectar.
- El Fire TV desaparece del selector tras reiniciarlo: repite el `adb connect`.
- Para desinstalar la versión debug: `adb uninstall org.jellyfin.androidtv.debug`.

## Idioma de la app

Ajustes → **Idioma** (debajo de *Subtítulos*): *Idioma de la tele* o cualquiera de las traducciones de Jellyfin
para Android TV (las carpetas `res/values-*`), con inglés y español primero y, en las demás, cuánto de la app
original está traducido. Las pantallas nuevas de Jellystick (`strings_modern_home.xml`) solo están en inglés y
español: en otros idiomas salen en inglés (las variantes de español, como es-MX, usan el español).

Se guarda en un archivo de preferencias propio (`app_language`) porque hace falta antes de que arranque nada:
cada actividad (`MainActivity`, `StartupActivity`, `ExternalPlayerActivity`) lo aplica en `attachBaseContext`
y la aplicación en sus recursos (textos de repositorios, notificaciones, salvapantallas). Al cambiarlo, la
pantalla se recrea en el nuevo idioma. Los títulos, sinopsis y géneros vienen del servidor, en el idioma de
sus metadatos. Código: `util/AppLanguage.kt` y `ui/settings/screen/language/`.

## Publicar el APK (para instalarlo con Downloader)

**Actualizar desde la app**: Ajustes → **Actualizar Jellystick** busca la última *release* de GitHub
(`jellystick.updateRepo` en `gradle.properties`, por defecto `alexcagira/jellystick`), y si su versión es más
nueva que la instalada la descarga y abre el instalador de Android, que la instala encima sin perder la sesión.
Para que funcione:

- La etiqueta de la release es la versión de la app: `v1.2.3` para `jellystick.version=1.2.3`. Sube
  `jellystick.version` antes de compilar cada APK y crea la release con esa etiqueta; tiene que ser mayor que la
  instalada (Android tampoco instala encima una versión menor). El nombre de la release puede ser
  "Jellystick v1.2.3".
- El botón mira las últimas 30 releases y elige la de versión más alta que tenga APK.
- La release lleva el APK como archivo adjunto (cualquier `.apk` sin "debug" en el nombre), firmado con tu clave.
- La primera vez Android pide permiso para que Jellystick instale apps: en el Fire TV, Configuración → Mi Fire TV →
  Opciones para desarrolladores → Instalar apps desconocidas → Jellystick. Luego se vuelve a pulsar el botón.
- La release no puede ser un borrador, y el repositorio tiene que ser público.
- Las primeras versiones se numeraban 13.x (la del Jellyfin original). Desde la 1.2.2 la versión es la de GitHub;
  el código de versión de Android lleva sumado 100 000 000 (`JELLYSTICK_VERSION_CODE_OFFSET` en
  `app/build.gradle.kts`) para que la 1.2.2 se instale encima de una 13.x sin desinstalar. Desde una 13.x el botón
  no ve la 1.2.2 como más nueva: esa se instala a mano una vez.
- El panel del servidor muestra la app como **Jellystick 1.2.2**.

La versión release se llama `com.jellystick.tv`, así que se instala al lado de la app oficial de Jellyfin
y de la versión debug (cada una con su propia sesión).

**1. Clave de firma (solo la primera vez).** Android Studio → *Build* → *Generate Signed App Bundle or APK*
→ **APK** → *Create new…*. Guárdala fuera del proyecto (p. ej. `C:/Users/Alex/jellystick.jks`) y apunta las
contraseñas y el alias. **No la pierdas**: las actualizaciones tienen que ir firmadas con la misma clave, o
habrá que desinstalar la app (y perder la sesión) para instalar la nueva.

**2. Configúrala en `local.properties`** (no se sube a Git; usa `/` en la ruta):

```properties
keystore.file=C:/Users/Alex/jellystick.jks
keystore.password=CONTRASEÑA_DEL_ALMACEN
signing.key.alias=jellystick
signing.key.password=CONTRASEÑA_DE_LA_CLAVE
```

**3. Versión.** En `gradle.properties`, sube `jellystick.version` antes de cada publicación
(`1.2.2` → `1.2.3` → `1.3.0`...), la misma que la etiqueta de la release (`v1.2.3`). Android solo instala una
actualización si su versión es mayor.

**4. Compilar.** *Build Variants* → `release`, y *Build* → *Generate App Bundles or APKs* → *Generate APKs*
(o `.\gradlew assembleRelease` en la Terminal). El APK queda en
`app/build/outputs/apk/release/jellystick-v1.2.2-release.apk`.

**5. Subirlo a GitHub.** Crea un repositorio **público** (p. ej. `jellystick`; puede tener solo un README) →
*Releases* → *Draft a new release* → etiqueta `v1.2.2` → arrastra el APK **renombrado a `jellystick.apk`**
→ *Publish release*. Este enlace apunta siempre al APK de la última release:

```
https://github.com/TU_USUARIO/jellystick/releases/latest/download/jellystick.apk
```

**6. Crear el código.** En [go.aftvnews.com](https://go.aftvnews.com/) pega ese enlace y te da un código
numérico (es permanente y público: cualquiera que lo escriba descarga el APK).

**7. Instalar en el Fire TV.** Ajustes → Mi Fire TV → Opciones para desarrolladores → *Instalar apps
desconocidas* → **Downloader: activado**. En Downloader escribe el código → se descarga → *Instalar*.

**Actualizar:** sube la versión, compila con la misma clave y publica una release nueva con el archivo
llamado igual (`jellystick.apk`). El mismo código descarga siempre la última y se instala encima,
sin perder la sesión.

Notas:

- La clave de TMDB de `local.properties` va dentro del APK; si lo compartes, cualquiera podría sacarla.
- Los Fire TV nuevos con Vega OS (p. ej. el Fire TV Stick 4K Select) no instalan APKs; el 4K Max sí.

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
| `../../music/share/` | Cola compartida (servidor web de la tele y código QR) |
| `../../music/nowplaying/` | Reproduciendo ahora y su salvapantallas |
| `../../search/modern/` | Búsqueda con etiquetas y voz |
| `../../collections/` | Colecciones por idioma de audio y sus pósters |
| `PosterActions.kt` / `IdentifyDialog.kt` | Menú de los pósters e Identificar |

La fuente de los títulos es Bebas Neue (licencia SIL OFL, ver `docs/fonts/BebasNeue-OFL.txt`).
