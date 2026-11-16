package org.jellyfin.androidtv.data.compat

class VideoOptions : AudioOptions() {
	var audioStreamIndex: Int? = null
	var subtitleStreamIndex: Int? = null
	var alwaysBurnInSubtitleWhenTranscoding: Boolean = false

	/** False: the server converts the video instead of copying it (playback fallback) */
	var allowVideoStreamCopy: Boolean = true

	/** False: the server converts the audio instead of copying it (playback fallback) */
	var allowAudioStreamCopy: Boolean = true
}
