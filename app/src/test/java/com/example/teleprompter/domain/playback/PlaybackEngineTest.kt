package com.example.teleprompter.domain.playback

import com.example.teleprompter.domain.model.SpeechUnit
import com.example.teleprompter.domain.model.FontScale
import com.example.teleprompter.domain.overlay.stepOverlayFontScale
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackEngineTest {
    @Test
    fun returnsTwoPreviousCurrentAndTwoNextUnits() {
        val units = (0 until 6).map { SpeechUnit("Sentence $it.", 2, 0) }
        val window = windowAround(units, currentIndex = 3)
        assertEquals(listOf(1, 2, 3, 4, 5), window.map { it.index })
        assertEquals(listOf(false, false, true, false, false), window.map { it.isCurrent })
    }

    @Test
    fun enforcesMinimumSegmentDuration() {
        val unit = SpeechUnit("One.", 1, 0)
        assertEquals(1.5, segmentDurationSeconds(unit, 160), 0.001)
    }

    @Test
    fun countdownSequenceIncludesEveryVisibleValueAndZero() {
        assertEquals(listOf(5, 4, 3, 2, 1, 0), countdownValues(5))
        assertEquals(listOf(0), countdownValues(0))
    }

    @Test
    fun mapsWholeScriptFractionToSegmentAndInnerProgress() {
        assertEquals(PlaybackPosition(index = 0, progress = 0f), positionForFraction(0f, unitCount = 4))
        assertEquals(PlaybackPosition(index = 1, progress = 0.5f), positionForFraction(0.375f, unitCount = 4))
        assertEquals(PlaybackPosition(index = 3, progress = 1f), positionForFraction(1f, unitCount = 4))
    }

    @Test
    fun clampsOverlayFontScaleAtBothEnds() {
        assertEquals(FontScale.SMALL, stepOverlayFontScale(FontScale.SMALL, increase = false))
        assertEquals(FontScale.MEDIUM, stepOverlayFontScale(FontScale.SMALL, increase = true))
        assertEquals(FontScale.EXTRA_LARGE, stepOverlayFontScale(FontScale.EXTRA_LARGE, increase = true))
    }

    @Test
    fun keepsLongSentenceAsOnePlaybackUnit() {
        val sentence = "Today, I’d like to invite you to imagine a garden."

        val units = playbackUnits(sentence)

        assertEquals(1, units.size)
        assertEquals(sentence, units.single().rawText)
    }

    @Test
    fun anchorsLongCurrentSentenceAtTopInsteadOfHidingItsTail() {
        val units = listOf(
            SpeechUnit("Before.", 1, 0),
            SpeechUnit("Another before.", 2, 0),
            SpeechUnit("We can see it in Chinese characters, hear it in ancient poems, and feel it in our festivals and family traditions.", 19, 0),
            SpeechUnit("After.", 1, 0)
        )

        assertEquals(2, autoScrollStartIndex(units, currentIndex = 2, contextItems = 2))
    }

    @Test
    fun usesCompactControlsInLandscapeToKeepMoreRoomForText() {
        assertEquals(PlaybackControlsArrangement.COMPACT, playbackControlsArrangement(landscape = true))
        assertEquals(PlaybackControlsArrangement.STACKED, playbackControlsArrangement(landscape = false))
    }
}
