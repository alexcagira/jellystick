## Jellystick: what's new compared to Jellyfin for Android TV

A fork of [jellyfin-androidtv](https://github.com/jellyfin/jellyfin-androidtv) built for the Fire TV Stick 4K Max.
Every new feature can be turned off in *Settings → Customization → Home* to get the classic interface back.

### Home
- Streaming-style home screen: floating glass navigation bar, media bar with title logos and a blurred background of the current slide.
- Rows: **Continue Watching** (in progress and next episodes, sorted by recent activity; it changes instantly when you leave a video, without waiting for the server, also on the movie and TV show pages), **Watchlist**, **Trending this week** ranked 1 to 10 (most watched on your server, TMDB optional; hidden libraries and copies of the same title don't count), **Latest Episodes** (by air date, not by date added), **Latest Films**, **Recently added** and **Favourites**.
- Poster badges: resolution (4K, 1080p…), **Dolby Vision**, **Atmos / TrueHD / Digital**, **HDR10+ / HDR / HLG** and **flags for the audio languages** (Latin American Spanish with the Mexican flag, Brazilian Portuguese…).
- Red *New episode / New season / New series* labels only for what **aired** in the last 14 days (an old season you just added gets no label).
- No duplicates when a title is in several libraries (e.g. one per language).
- Configurable section order. Libraries in the bar: move, hide, browse and scan (admin) with a long press on OK.
- Instant start: shows what it showed last time and refreshes in the background.
- New titles on the server show up by themselves on the home screen, library pages and details pages as soon as the server announces them (sooner for administrators).
- **IMDb stars** on posters, the media bar, details pages and the pause screen (from IMDb's official dataset, updated every week).

### Movie and TV show libraries
- Each library gets its own page, like the home screen: media bar, pills with the other libraries of the same kind, **Collections** and **Browse**.
- Rows: the library's Continue Watching, Latest Films / Latest Episodes (by air date; 16:9 with quality and flags), Recently added, *Because you watched… / liked… / have … in your Watchlist*, IMDb Top 250, collections, studios and networks, genres with their own titles, tags and **seasonal rows** (Halloween, Christmas, Valentine's Day, summer). Long rows end with **See all**.
- Loads in steps and is cached on the device, so it shows up right away.
- **Browse**: paged grid with an **A–Z** jump bar, filters (unwatched, watched, favourites), genres merged across languages (Action/Acción), Spanish age ratings, **audio language**, sorting, shuffle and tags.
- An **IMDb Top 250** collection with your movies, synced every week.
- **Audio language collections** (with a flag poster) that update themselves every day.
- **Series collections by streaming service** (Netflix, HBO Max, Prime Video, Disney+, Apple TV+…) or TV network, also kept up to date. The *Collections* pill of TV show libraries only lists series collections, and the one of film libraries leaves them out.

### Details pages
- New pages for movies, series, seasons and people: full-screen backdrop with logo, rating, genres and overview; Next up, seasons, episode carousel, cast and similar titles. They open at once and the slowest part (*More like this*) comes after.
- **IMDb** rating and, for movies, the **Letterboxd** average (0 to 5).
- **Play / Resume S01:E05 / Resume from 1:05:32** button with **⏮** next to it to start from the beginning, favourite, trailer, watched, file info and **Watchlist** (synced with the KefinTweaks watchlist on the web).
- Switch seasons without leaving the page, ✓ to mark episodes as watched, and **quality and flags on every episode**.
- **Series theme song** with fade in and out.
- Poster menu (long press on OK): play, watched/unwatched, **add to watchlist**, more info, go to the series or season, file info and **Identify**, like on the web (admin).

### Player
- New controls: a clean screen while playing, seeking with thumbnails (the video only jumps when you press **OK**; otherwise it keeps playing where it was), chapters on the bar, "Ends at…" and a *You're watching* pause screen.
- **Subtitles move up** above the seek bar while the controls show and go back down when they hide.
- Menus for audio, subtitles, chapters, quality, speed, **subtitle offset** and **playback mode**.
- **Subtitles your way** with a preview: one-press styles (Netflix-like, classic, bold, box, yellow), outline or shadow, font, size, weight and colours.
- **☰ in the player: stream info** in a glass panel on the right: direct play, remux or transcoding and why, the file, the original and sent video and audio, the Fire TV decoders, subtitles (and whether the server burns them in), dropped frames and buffer. Updated every second without stopping the video.
- Fixed: some episodes of long series (South Park) **did nothing** when played from *Continue Watching* or the details page; when the server's episode list leaves them out, the app reads them from the series folder.
- **Skip intro / credits / recap** with Jellyfin media segments or Intro Skipper.
- When a file fails in direct play, the app automatically retries with **Dolby Vision as HDR10, converted audio or transcoding** from the same position, and remembers it for the whole series.
- **Dolby Vision + HDR10+ played directly, as Dolby Vision** on the Fire TV 4K / 4K Max (e.g. *The Rings of Power*), like Plex: the server used to remove Dolby Vision (remux) and the TV showed HDR. The app hides the HDR10+ metadata from the Dolby Vision decoder, which is what made the screen black. Atmos audio goes through as passthrough.
- Fire TV options: **play Dolby Vision as HDR10** (no blocks of earlier frames: the Dolby Vision metadata is removed before decoding), and *audio offload* can be turned off (avoids frozen video with Atmos).

### Music
- New music page: media bar with recommendations (*Because you listen to…*), **stations** by artist and genre, configurable rows and quick links.
- Album, playlist, artist and genre pages with a **heart on every song** and an automatic **Favourite Tracks** playlist.
- Redesigned **Now Playing**: optional lyrics, ⏪/⏩ on the remote change songs, the next cover is already loaded, and a **screensaver** with lyrics and a **Next up** card (cover, song and album of the next one) in the last 10 seconds.
- The **☰ button** on the remote stops the music from any screen (like *Stop*); in the video player it opens the stream info.
- A *now playing* bubble in the navigation bar.
- **Shared queue**: a QR code lets anyone on your Wi-Fi search your library and add songs or albums from their phone. When the queue ends, what played is kept: a new song starts from itself and the earlier ones stay in the queue to play again, until you press ☰.
- Fixes: the queue no longer stops after 5 or 6 songs, and the Fire TV doesn't go to sleep while music is playing.

### Search
- New search with **voice**, results in sections (Films, Series, Collections, Episodes, People and Music) and posters with every badge.
- Without text: genres as colour tiles and popular tags; music-only search with music genres.

### Fire TV
- Its own **Jellystick** app (`com.jellystick.tv`) with a green name, logo (its own rhombus) and banner: installs next to the official app. On the Fire TV home screen the logo sits on the grey of the tile, like other apps. The server dashboard shows it as *Jellystick*.
- The player opens sooner and goes first: pressing play pauses whatever else was loading, and the next episodes of the queue are loaded once it plays.
- **App language** in Settings (under Subtitles): the TV's or any of Jellyfin's translations. Jellystick's new screens are in English and Spanish.
- **Update from Settings**: downloads and installs the latest version published on GitHub. The app version is the release's (v1.2.2, v1.2.3…).
- Optimized release build (R8 + baseline profile), smooth scrolling between rows, and focus returns to where you were.
