## Jellystick: novedades respecto a Jellyfin para Android TV

Fork de [jellyfin-androidtv](https://github.com/jellyfin/jellyfin-androidtv) pensado para el Fire TV Stick 4K Max.
Todo lo nuevo se puede desactivar en *Ajustes → Personalización → Inicio* para volver a la interfaz clásica.

### Inicio
- Pantalla de inicio estilo plataforma de streaming: barra de navegación flotante con efecto cristal, media bar con logos y fondo difuminado de la diapositiva actual.
- Filas: **Continue Watching** (a medias y siguientes episodios, por actividad reciente; cambia al instante al salir de un vídeo, sin esperar al servidor, también en las páginas de películas y series), **Watchlist**, **Trending this week** con números del 1 al 10: lo que es **tendencia esta semana en TMDB** (con tu clave gratuita de TMDB, en Ajustes → Personalización → Inicio) y después en **IMDb** (votos ganados por encima de lo habitual, del archivo oficial de IMDb), y para los huecos lo más visto en tu servidor; sin contar bibliotecas ocultas ni copias del mismo título, **Latest Episodes** (por fecha de emisión, no de añadido), **Latest Films**, **Recently added** y **Favourites**.
- Etiquetas en los pósters: resolución (4K, 1080p…), **Dolby Vision**, **Atmos / TrueHD / Digital**, **HDR10+ / HDR / HLG** y **banderas de los idiomas de audio** (español latino con bandera de México, portugués de Brasil…).
- Etiquetas rojas *New episode / New season / New series* solo para lo **emitido** en los últimos 14 días (una temporada antigua recién añadida no lleva etiqueta).
- Sin duplicados cuando un título está en varias bibliotecas (p. ej. una por idioma).
- Orden de las secciones configurable. Bibliotecas de la barra: mover, ocultar, explorar y escanear (admin) manteniendo OK.
- Arranque instantáneo: muestra lo de la última vez y se actualiza por detrás.
- Los títulos nuevos del servidor aparecen solos en el inicio, las bibliotecas y las fichas en cuanto el servidor avisa (antes si eres administrador).
- **Estrellas de IMDb** en pósters, media bar, fichas y pausa (del conjunto de datos oficial de IMDb, actualizado cada semana).

### Bibliotecas de películas y series
- Página propia de cada biblioteca, como el inicio: media bar, pills con las bibliotecas del mismo tipo, **Colecciones** y **Explorar**.
- Filas: Continue Watching de esa biblioteca, **Trending this week** de esa biblioteca (TMDB e IMDb), Últimas películas / Últimos episodios (por fecha de emisión; 16:9 con calidad y banderas), Añadido recientemente, *Porque viste… / te gustó… / está en tu Watchlist*, IMDb Top 250, colecciones, estudios y cadenas, géneros con títulos propios, etiquetas y **filas de temporada** (Halloween, Navidad, San Valentín, verano). Las filas largas acaban en **Ver todo**.
- Carga por partes y caché en el dispositivo: sale al momento.
- **Explorar**: cuadrícula por páginas con salto **A–Z**, filtros (sin ver, vistas, favoritos), géneros sin duplicar entre idiomas (Action/Acción), clasificación por edad española, **idioma de audio**, orden, al azar y etiquetas.
- Colección **IMDb Top 250** con tus películas, sincronizada cada semana.
- **Colecciones por idioma de audio** (con póster de bandera) que se actualizan solas cada día.
- **Colecciones de series por plataforma** (Netflix, HBO Max, Prime Video, Disney+, Apple TV+, Movistar Plus+…) o por cadena, también al día. La pill *Colecciones* de las series solo muestra colecciones de series, y la de películas las deja fuera.

### Fichas
- Fichas nuevas de películas, series, temporadas y personas: fondo a pantalla completa con logo, valoración, géneros y sinopsis; Next up, temporadas, carrusel de episodios, reparto y similares. Se abren al momento y lo más lento (*Más como esto*) llega después.
- Nota de **IMDb** y, en las películas, la media de **Letterboxd** (de 0 a 5).
- Botón **Reproducir / Reanudar T01:E05 / Reanudar desde 1:05:32** con **⏮** al lado para empezar desde el principio, favoritos, tráiler, visto, información del archivo y **Watchlist** (sincronizada con la de KefinTweaks en la web).
- Cambio de temporada sin salir de la página (en series largas las pastillas se desplazan solas hasta la temporada actual y siguen al cursor), ✓ para marcar episodios como vistos y **calidad y banderas en cada episodio**.
- **Sintonía de la serie** (theme song) con fundidos.
- Menú de los pósters (mantener OK): reproducir, visto/no visto, **añadir a la watchlist**, más información, ir a la serie o la temporada, información del archivo e **Identificar** como en la web (admin).

### Reproductor
- Controles nuevos: pantalla limpia al reproducir, búsqueda con miniaturas (el vídeo solo salta al pulsar **OK**; si no, sigue donde estaba), capítulos en la barra, "Termina a las…" y pantalla de pausa *Estás viendo*.
- Los **subtítulos suben** por encima de la barra mientras se ven los controles y vuelven a bajar al ocultarse.
- Menús de audio, subtítulos, escenas, calidad, velocidad, **desfase de subtítulos** y **modo de reproducción**.
- **Subtítulos a tu gusto** con vista previa: estilos de un toque (tipo Netflix, clásico, negrita, caja, amarillo), contorno o sombra, fuente, tamaño, negrita y colores.
- **☰ en el reproductor: información de reproducción** en un panel de cristal a la derecha: reproducción directa, remux o transcodificación y el motivo, archivo, vídeo y audio originales y enviados, decodificadores del Fire TV, subtítulos (y si el servidor los graba), fotogramas perdidos y búfer. Se actualiza cada segundo sin parar el vídeo.
- Arreglado: algunos episodios de series largas (South Park) **no hacían nada** al darles a reproducir desde *Continue Watching* o la ficha; si la lista de episodios del servidor no los incluye, la app los lee de la carpeta de la serie.
- **Saltar intro / créditos / resumen** con los segmentos de Jellyfin o Intro Skipper.
- Si un archivo no funciona en reproducción directa, la app reintenta sola con **Dolby Vision como HDR10, audio convertido o transcodificación**, desde el mismo punto, y lo recuerda para toda la serie.
- **Dolby Vision + HDR10+ en directo y en Dolby Vision** en el Fire TV 4K / 4K Max (p. ej. *Los anillos de poder*), como Plex: antes el servidor quitaba Dolby Vision (remux) y la tele mostraba HDR. La app oculta los metadatos HDR10+ al decodificador Dolby Vision, que era lo que dejaba la pantalla en negro. El audio Atmos va en *passthrough*.
- **4K HLG (y HDR) que se veía como mármol**, con manchas y curvas de nivel: el vídeo ya solo lo decodifica el chip del Fire TV. Antes, si el decodificador del chip no arrancaba (o con *Preferir FFmpeg*), lo hacía uno por software que no sabe de 10 bits; ahora, si el chip no puede, convierte el servidor.
- *Modo de reproducción* (⇄) con dos opciones más para esos casos: **HLG a SDR** (o HDR10 / Dolby Vision a SDR: lo convierte el servidor, a la calidad elegida) y **Reempaquetar (remux) a calidad original** (el vídeo del archivo tal cual aunque pase del límite de calidad del reproductor). Se recuerdan para toda la serie.
- Opciones para Fire TV: **Dolby Vision como HDR10** (sin bloques de fotogramas anteriores: se quitan los metadatos Dolby Vision antes de decodificar) y *audio offload* desactivable (evita vídeo congelado con Atmos).

### TV en directo
- **Página de TV en directo** con pestañas **Programas**, **Guía**, **Canales** y **Favoritos** (como la web de Jellyfin), barra de navegación de cristal y fondo difuminado.
- **Programas** (la pestaña de entrada): **media bar con lo destacado de la guía** como la del inicio (partidos y demás deportes en directo o a punto de empezar, películas, estrenos) con el logo del canal y la etiqueta *EN DIRECTO*, y las filas de la web: **En emisión**, **Series y programas**, **Películas**, **Deportes**, **Infantil** y **Noticias**, con la hora (*20:00 – 21:00* o *dom 22 nov 10:30*), el episodio y el canal. Los programas sin imagen llevan el logo del canal entero.
- **Canales**: todos los canales en cuadrícula (logo, número, nombre, lo que ponen, hasta cuándo y cuánto lleva) con **buscador de canales** y filtro **Todos / Favoritos** al lado. Mantener OK en un canal lo **añade a favoritos** o lo quita.
- **Favoritos**: tus canales favoritos con la imagen de lo que emiten.
- **Guía**: pide los programas (EPG) con la hora del servidor. Con *No program data available* en todos los canales, la app pedía las horas según el reloj del Fire TV, y si su hora o su zona horaria no coinciden con las del servidor pedía horas sin programas. Ahora pide las horas del servidor y coloca los programas en la hora del Fire TV (un aviso dice si no coinciden). Además, cada programa se lee por separado y los canales se piden en grupos pequeños y con más tiempo; si la guía sigue vacía, un aviso dice qué ha recibido. Lo mismo en la guía del reproductor.
- Arreglado: **los canales se quedaban cargando** después de abrir la página de TV en directo. Las imágenes de la guía, que el servidor descarga del EPG y a veces tardan, ocupaban la cola de conexiones del vídeo: ahora las imágenes van en su propia cola, la página pide las filas de dos en dos y solo de las próximas 12 horas, y deja de cargar en cuanto se sintoniza un canal.
- **Página de cada programa**: su imagen de fondo, el logo del canal, horario, lo que queda, **Ver ahora**, **Grabar** / **Grabar serie** y lo que pone después el canal.
- **Reproductor moderno también en directo**: logo del canal, el programa con *EN DIRECTO*, la barra del programa de su inicio a su fin, *Termina a las…*; ↓ abre los canales y los botones la guía y la información. **☰ ya no abre la guía**: como en películas y series, muestra la información de reproducción.
- Arreglado: tras abrir la guía o el selector de canales y cerrarlos sin cambiar de canal, OK ya no volvía a mostrar los controles con la barra de tiempo.
- La guía y el selector de canales con los colores y las letras del resto de la app: tarjetas oscuras redondeadas, en blanco la que tiene el foco.

### Música
- Página de música nueva: media bar con recomendaciones (*Porque escuchas a…*), **radios** por artista y género, filas configurables y accesos rápidos.
- Páginas de álbum, lista, artista y género con **corazón en cada canción** y lista automática **Favourite Tracks**.
- **Reproduciendo ahora** rediseñado: letra opcional, ⏪/⏩ del mando cambian de canción, la siguiente portada ya cargada y **salvapantallas** con letra y aviso **Next up** (portada, canción y álbum de la siguiente) en los últimos 10 segundos. La barra de progreso se corrige cada segundo con la posición real (antes podía llegar al final antes que la canción en el salvapantallas).
- El botón **☰ del mando** detiene la música desde cualquier pantalla (como *Detener*); en el reproductor de vídeo abre la información de reproducción.
- Burbuja de *música sonando* en la barra de navegación.
- **Cola compartida**: código QR para que cualquiera en tu Wi-Fi busque en tu biblioteca y añada canciones o álbumes desde el móvil. Si la cola se acaba, lo que sonó se guarda: una canción nueva empieza por ella y las anteriores siguen en la cola para volver a ponerlas, hasta pulsar ☰.
- Arreglos: la cola ya no se para tras 5 o 6 canciones y el Fire TV no entra en reposo con música sonando.

### Búsqueda
- Búsqueda nueva con **voz**, resultados por secciones (Películas, Series, Colecciones, Episodios, Personas y Música) y pósters con todas las etiquetas.
- Sin texto: géneros como tiles de color y etiquetas populares; búsqueda solo de música con géneros musicales.

### Fire TV
- App propia **Jellystick** (`com.jellystick.tv`) con nombre, logo (un rombo propio) y banner verdes: se instala junto a la app oficial. En el inicio del Fire TV el logo va sobre el gris de la casilla, como las demás apps. El panel del servidor la muestra como *Jellystick*.
- El reproductor se abre antes y tiene prioridad: al darle a reproducir, lo demás que se estaba cargando espera, y los episodios siguientes de la cola se piden ya reproduciendo.
- **Idioma de la app** en Ajustes (debajo de Subtítulos): el de la tele o cualquiera de las traducciones de Jellyfin. Las pantallas nuevas de Jellystick están en inglés y español.
- **Actualizar desde Ajustes**: descarga e instala la última versión publicada en GitHub. La versión de la app es la de la release (v1.2.2, v1.2.3…).
- Versión release optimizada (R8 + baseline profile), scroll suave entre filas y el foco vuelve al elemento en el que estabas.
