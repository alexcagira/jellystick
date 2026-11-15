package org.jellyfin.androidtv.ui.library.modern

import androidx.compose.ui.graphics.Color
import org.jellyfin.androidtv.R
import java.text.Normalizer
import java.util.UUID
import kotlin.math.absoluteValue

/**
 * Icon and color of a genre (search tiles), recognized by name in English or Spanish. The order
 * matters: the first match wins ("Anime" before "Animation", "Musical" before "Music"...).
 */
internal enum class GenreStyle(
	val icon: Int,
	val color: Color,
	val keywords: List<String>,
	/** Name shown in English and in Spanish */
	val english: String,
	val spanish: String,
) {
	TV_MOVIE(R.drawable.ic_genre_tvmovie, Color(0xFF2C6060), listOf("tv movie", "pelicula de tv", "telefilm"), "TV Movie", "Película de TV"),
	SCIFI(R.drawable.ic_genre_scifi, Color(0xFF2F5A8C), listOf("science fiction", "sci-fi", "sci fi", "scifi", "ciencia ficcion", "ciencia-ficcion"), "Science Fiction", "Ciencia ficción"),
	ANIME(R.drawable.ic_genre_animation, Color(0xFF8C4E26), listOf("anime"), "Anime", "Anime"),
	ANIMATION(R.drawable.ic_genre_animation, Color(0xFF8C5228), listOf("anima"), "Animation", "Animación"),
	MUSICAL(R.drawable.ic_genre_musical, Color(0xFF7A3360), listOf("musical"), "Musical", "Musical"),
	MUSIC(R.drawable.ic_genre_music, Color(0xFF7A3346), listOf("music", "musica"), "Music", "Música"),
	MARTIAL_ARTS(R.drawable.ic_genre_martial, Color(0xFF7A3330), listOf("martial", "marcial"), "Martial Arts", "Artes marciales"),
	GAME_SHOW(R.drawable.ic_genre_gameshow, Color(0xFF8A6A22), listOf("game show", "game-show", "concurso"), "Game Show", "Concurso"),
	MINISERIES(R.drawable.ic_genre_miniseries, Color(0xFF3E4B85), listOf("mini"), "Miniseries", "Miniserie"),
	DOCUMENTARY(R.drawable.ic_genre_documentary, Color(0xFF2C6066), listOf("document"), "Documentary", "Documental"),
	WAR(R.drawable.ic_genre_war, Color(0xFF3D5C3C), listOf("war", "belic", "guerra"), "War", "Bélica"),
	KIDS(R.drawable.ic_genre_kids, Color(0xFF2E5C8A), listOf("kid", "infantil", "children", "ninos"), "Kids", "Infantil"),
	REALITY(R.drawable.ic_genre_reality, Color(0xFF5E3D7A), listOf("reality"), "Reality", "Reality"),
	TALK(R.drawable.ic_genre_talk, Color(0xFF5A4A6A), listOf("talk", "entrevista"), "Talk Show", "Entrevistas"),
	NEWS(R.drawable.ic_genre_news, Color(0xFF44506A), listOf("news", "noticia"), "News", "Noticias"),
	SPORT(R.drawable.ic_genre_sport, Color(0xFF2F6A40), listOf("sport", "deporte"), "Sport", "Deporte"),
	BIOGRAPHY(R.drawable.ic_genre_biography, Color(0xFF57506A), listOf("biograph", "biografi"), "Biography", "Biografía"),
	POLITICS(R.drawable.ic_genre_politics, Color(0xFF464E7A), listOf("politic"), "Politics", "Política"),
	HISTORY(R.drawable.ic_genre_history, Color(0xFF5E5040), listOf("histor"), "History", "Historia"),
	INDIE(R.drawable.ic_genre_indie, Color(0xFF2D5F55), listOf("indie", "independ"), "Indie", "Independiente"),
	FOOD(R.drawable.ic_genre_food, Color(0xFF8C4A2C), listOf("food", "cocina", "gastronom"), "Food", "Cocina"),
	FAMILY(R.drawable.ic_genre_family, Color(0xFF8A5A22), listOf("famil"), "Family", "Familia"),
	MYSTERY(R.drawable.ic_genre_mystery, Color(0xFF34437A), listOf("myster", "misterio"), "Mystery", "Misterio"),
	FANTASY(R.drawable.ic_genre_fantasy, Color(0xFF5B3F86), listOf("fantas"), "Fantasy", "Fantasía"),
	CRIME(R.drawable.ic_genre_crime, Color(0xFF3F4660), listOf("crime", "crimen", "criminal"), "Crime", "Crimen"),
	ROMANCE(R.drawable.ic_genre_romance, Color(0xFF7E3355), listOf("roman"), "Romance", "Romance"),
	HORROR(R.drawable.ic_genre_horror, Color(0xFF5E3640), listOf("horror", "terror"), "Horror", "Terror"),
	THRILLER(R.drawable.ic_genre_thriller, Color(0xFF3E4A55), listOf("thrill", "suspens", "intriga"), "Thriller", "Suspense"),
	ADVENTURE(R.drawable.ic_genre_adventure, Color(0xFF2F6447), listOf("advent", "aventura"), "Adventure", "Aventura"),
	WESTERN(R.drawable.ic_genre_adventure, Color(0xFF7A5530), listOf("western"), "Western", "Western"),
	DRAMA(R.drawable.ic_genre_drama, Color(0xFF4A5590), listOf("drama"), "Drama", "Drama"),
	COMEDY(R.drawable.ic_genre_comedy, Color(0xFF8A6420), listOf("comed", "humor"), "Comedy", "Comedia"),
	ACTION(R.drawable.ic_genre_action, Color(0xFF8E3B3B), listOf("action", "accion"), "Action", "Acción");

	companion object {
		private val fallbackColors = listOf(
			Color(0xFF4A5590), Color(0xFF2F6447), Color(0xFF8A6420), Color(0xFF5B3F86),
			Color(0xFF2C6066), Color(0xFF7E3355), Color(0xFF2F5A8C), Color(0xFF8C5228),
		)

		fun of(name: String): GenreStyle? {
			val normalized = name.normalizedGenre()
			return entries.firstOrNull { style -> style.keywords.any { normalized.contains(it) } }
		}

		fun iconOf(name: String): Int = of(name)?.icon ?: R.drawable.ic_genre_indie

		fun colorOf(name: String): Color = of(name)?.color
			?: fallbackColors[name.lowercase().hashCode().absoluteValue % fallbackColors.size]

		/**
		 * One chip per genre: the same genre often comes in English and in Spanish (libraries with
		 * metadata in each language, "Action" and "Acción"); they are merged and shown with the name
		 * in the language of the app.
		 */
		fun merge(genres: List<Pair<String, UUID?>>, spanish: Boolean): List<BrowseChip> {
			val groups = LinkedHashMap<String, MutableList<Pair<String, UUID?>>>()
			for (genre in genres) {
				val key = of(genre.first)?.name ?: genre.first.normalizedGenre()
				groups.getOrPut(key) { mutableListOf() } += genre
			}

			return groups.map { (key, members) ->
				val style = entries.firstOrNull { it.name == key }
				BrowseChip(
					name = style?.let { if (spanish) it.spanish else it.english } ?: members.first().first,
					names = members.map { it.first }.distinct(),
					ids = members.mapNotNull { it.second }.distinct(),
				)
			}.sortedBy { it.name.normalizedGenre() }
		}
	}
}

/** Lowercase without accents: "Acción" → "accion" */
internal fun String.normalizedGenre(): String =
	Normalizer.normalize(lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

/** Icon and color of a music genre (music search page), recognized by name */
internal enum class MusicGenreStyle(
	val icon: Int,
	val color: Color,
	val keywords: List<String>,
) {
	SOUNDTRACK(R.drawable.ic_genre_indie, Color(0xFF2C6060), listOf("soundtrack", "score", "banda sonora", "film")),
	KPOP(R.drawable.ic_music_kpop, Color(0xFF7E3360), listOf("k-pop", "kpop", "j-pop", "jpop", "c-pop")),
	HIP_HOP(R.drawable.ic_music_hiphop, Color(0xFF5B3F86), listOf("hip hop", "hip-hop", "hiphop", "rap", "trap", "grime", "drill")),
	RNB(R.drawable.ic_genre_romance, Color(0xFF7E3355), listOf("r&b", "rnb", "soul", "funk")),
	LATIN(R.drawable.ic_music_latin, Color(0xFF8C4A2C), listOf("reggaeton", "latin", "salsa", "bachata", "cumbia", "merengue", "flamenco", "rumba", "tango", "bossa", "samba", "urbano")),
	REGGAE(R.drawable.ic_music_reggae, Color(0xFF2F6A40), listOf("reggae", "ska", "dancehall", "dub")),
	METAL(R.drawable.ic_genre_horror, Color(0xFF3E3E48), listOf("metal")),
	PUNK(R.drawable.ic_music_punk, Color(0xFF8E3B3B), listOf("punk", "hardcore", "grunge", "emo")),
	ELECTRONIC(R.drawable.ic_music_electronic, Color(0xFF2F5A8C), listOf("electro", "edm", "techno", "house", "trance", "dubstep", "drum", "bass", "idm", "synth")),
	DANCE(R.drawable.ic_music_dance, Color(0xFF7A3346), listOf("dance", "disco")),
	AMBIENT(R.drawable.ic_music_ambient, Color(0xFF2D5F55), listOf("ambient", "chill", "lo-fi", "lofi", "new age", "relax", "downtempo")),
	CLASSICAL(R.drawable.ic_genre_musical, Color(0xFF5E5040), listOf("classical", "clasica", "orchestr", "opera", "baroque", "symphon")),
	JAZZ(R.drawable.ic_music_jazz, Color(0xFF4A5590), listOf("jazz", "swing", "bebop")),
	BLUES(R.drawable.ic_music_blues, Color(0xFF34437A), listOf("blues")),
	COUNTRY(R.drawable.ic_music_country, Color(0xFF7A5530), listOf("country", "folk", "americana", "bluegrass")),
	GOSPEL(R.drawable.ic_music_gospel, Color(0xFF57506A), listOf("gospel", "christian", "cristiana", "religio", "worship")),
	INDIE(R.drawable.ic_music_indie, Color(0xFF2F6447), listOf("indie", "alternativ")),
	GAME(R.drawable.ic_music_game, Color(0xFF3E4B85), listOf("game", "videojuego", "chiptune")),
	OLDIES(R.drawable.ic_music_oldies, Color(0xFF8A6420), listOf("oldies", "retro", "60s", "70s", "80s", "90s")),
	SINGER_SONGWRITER(R.drawable.ic_music_singer, Color(0xFF5A4A6A), listOf("singer", "songwriter", "cantautor", "acoustic", "acustic")),
	WORLD(R.drawable.ic_genre_documentary, Color(0xFF2C6066), listOf("world", "mundo", "african", "celtic")),
	POP(R.drawable.ic_music_pop, Color(0xFF8C5228), listOf("pop")),
	ROCK(R.drawable.ic_music_rock, Color(0xFF8E3B3B), listOf("rock"));

	companion object {
		fun of(name: String): MusicGenreStyle? {
			val normalized = name.normalizedGenre()
			return entries.firstOrNull { style -> style.keywords.any { normalized.contains(it) } }
		}

		fun iconOf(name: String): Int = of(name)?.icon ?: R.drawable.ic_genre_music

		fun colorOf(name: String): Color = of(name)?.color ?: GenreStyle.colorOf(name)
	}
}
