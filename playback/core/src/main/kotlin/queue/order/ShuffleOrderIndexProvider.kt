package org.jellyfin.playback.core.queue.order

internal class ShuffleOrderIndexProvider : OrderIndexProvider {
	private val nextIndices = mutableListOf<Int>()

	override fun reset() = nextIndices.clear()

	override fun provideIndices(
		amount: Int,
		size: Int,
		playedIndices: Collection<Int>,
		currentIndex: Int,
	): Collection<Int> {
		// Forget picks that are no longer valid (the queue changed, or they were played already)
		nextIndices.removeAll { it >= size || it == currentIndex || it in playedIndices }

		// Pick the missing ones among the entries that were not played yet. Only real indices:
		// an index past the end stopped the playback
		val remaining = (0 until size)
			.filterNot { it == currentIndex || it in playedIndices || it in nextIndices }
			.toMutableList()
		while (nextIndices.size < amount && remaining.isNotEmpty()) {
			val index = remaining.random()
			remaining.remove(index)
			nextIndices.add(index)
		}

		return nextIndices.take(amount)
	}

	override fun notifyRemoved(index: Int) {
		nextIndices.removeAll { it == index }
		nextIndices.replaceAll { if (it > index) it - 1 else it }
	}

	override fun useNextIndex() {
		if (nextIndices.isNotEmpty()) nextIndices.removeAt(0)
	}
}
