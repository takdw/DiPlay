package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BydClusterSongTest {
    private fun expected(text: String, playing: Boolean, line: String = text,
        durationMillis: Long? = null, elapsedMillis: Long? = null) =
        ClusterSong(text, playing, line, artist = text.substringAfter(" — ", "").takeIf { it.isNotEmpty() },
            durationMillis = durationMillis, elapsedMillis = elapsedMillis)
    private fun update(block: com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder.() -> Unit) =
        Iap2Messages.buildRaw(ClusterSongState.NOW_PLAYING_UPDATE, block)

    @Test
    fun followsTitleArtistAndPlaybackStatus() {
        val state = ClusterSongState()

        assertEquals(expected("Numb — Linkin Park", false, "Numb"),
            state.accept(update { group(0) { string(1, "Numb"); string(12, "Linkin Park") } }))
        assertEquals(expected("Numb — Linkin Park", true, "Numb"), state.accept(update { group(1) { u8(0, 1) } }))
        assertEquals(120_706L, state.accept(update { group(1) { u32(1, 120_706L) } })!!.elapsedMillis)
        assertEquals(expected("Numb — Linkin Park", false, "Numb", elapsedMillis = 120_706L), state.accept(update { group(1) { u8(0, 2) } }))
        // A title-only incremental update retains the last artist.
        assertEquals(expected("Podcast — Linkin Park", false, "Podcast", elapsedMillis = 120_706L), state.accept(update { group(0) { string(1, "Podcast") } }))
        assertEquals(expected("Podcast — Host", false, "Podcast", elapsedMillis = 120_706L), state.accept(update { group(0) { string(12, "Host") } }))
    }

    @Test
    fun nothingWithoutATitleOrForOtherMessages() {
        val state = ClusterSongState()
        assertNull(state.accept(update { group(1) { u8(0, 1) } }))
        assertNull(state.accept(Iap2Messages.buildRaw(0x5201) { group(0) { string(1, "Numb") } }))
        assertNull(state.current())

        state.accept(update { group(0) { string(1, "Numb") } })
        state.clear()
        assertNull(state.current())
    }

    @Test
    fun clearedTitlesForgetThePreviousSongUntilANewTitleArrives() {
        val state = ClusterSongState()
        state.accept(update { group(0) { string(1, "Previous song"); string(12, "Artist") } })
        state.accept(update { group(0) { string(1, "") } })
        assertNull(state.current())
        state.accept(update { group(1) { u8(0, 1) } })
        assertNull(state.current())
        assertEquals(expected("Next song", true),
            state.accept(update { group(0) { string(1, "Next song") } }))
        state.accept(update { group(0) { string(1, "  "); string(12, "Stale artist") } })
        assertNull(state.current())
        assertEquals(expected("After clear", true),
            state.accept(update { group(0) { string(1, "After clear") } }))
    }

    @Test
    fun titleOnlyUpdatesRetainArtistAndPlaybackWhenOtherFieldsAreOmitted() {
        val state = ClusterSongState()
        state.accept(update {
            group(0) { string(1, "Track"); string(12, "Artist") }
            group(1) { u8(0, 1) }
        })
        assertEquals(expected("Lyric line — Artist", true, "Lyric line"),
            state.accept(update { group(0) { string(1, "Lyric line") } }))
        assertEquals(180_000L, state.accept(update { group(0) { u32(4, 180_000L) } })!!.durationMillis)
        assertEquals(expected("Lyric line — Artist", true, "Lyric line", durationMillis = 180_000L), state.current())
    }

    @Test
    fun explicitEmptyArtistClearsItWithoutChangingTitleOrPlayback() {
        val state = ClusterSongState()
        state.accept(update { group(0) { string(1, "Track"); string(12, "Artist") } })
        assertEquals(expected("Track", false),
            state.accept(update { group(0) { string(12, "") } }))
        assertEquals(expected("Next line", false),
            state.accept(update { group(0) { string(1, "Next line") } }))
    }

    @Test
    fun completeTrackUpdateReplacesBothTitleAndArtist() {
        val state = ClusterSongState()
        state.accept(update { group(0) { string(1, "First track"); string(12, "First artist") } })
        assertEquals(expected("Second track — Second artist", false, "Second track"),
            state.accept(update { group(0) { string(1, "Second track"); string(12, "Second artist") } }))
        assertEquals(expected("Third track", false),
            state.accept(update { group(0) { string(1, "Third track"); string(12, "") } }))
    }

    @Test
    fun textFitsTheDashboard() {
        assertNull(ClusterSongState.text("  ", "Artist"))
        assertEquals("Title", ClusterSongState.text(" Title ", ""))

        val long = ClusterSongState.text("Пісня".repeat(40), "Виконавець")!!
        assertTrue(long.toByteArray(Charsets.UTF_16LE).size <= ClusterSongState.MAX_TEXT_BYTES)
        assertEquals(127, long.length)

        // An emoji is never cut in half.
        val emoji = ClusterSongState.text("a" + "🎵".repeat(100), null)!!
        assertTrue(emoji.toByteArray(Charsets.UTF_16LE).size <= ClusterSongState.MAX_TEXT_BYTES)
        assertTrue(!Character.isHighSurrogate(emoji.last()))
    }

    @Test fun sparsePositionUpdatesAdvancePauseSeekAndClampAtTheTrackEnd() {
        var now = 1_000L
        val state = ClusterSongState { now }
        state.accept(update {
            group(0) { string(1, "Track"); string(12, "Artist"); u32(4, 180_000) }
            group(1) { u8(0, 1); u32(1, 20_000) }
        })
        now = 6_000
        assertEquals(25_000L, state.current()!!.position(now))
        state.accept(update { group(1) { u8(0, 2) } })
        now = 16_000
        assertEquals(25_000L, state.current()!!.position(now))
        state.accept(update { group(1) { u8(0, 1); u32(1, 175_000) } })
        now = 26_000
        assertEquals(180_000L, state.current()!!.position(now))
        state.accept(update { group(1) { u32(1, 5_000) } })
        assertEquals(5_000L, state.current()!!.position(now))
        state.accept(update { group(0) { string(1, "") } })
        state.accept(update { group(0) { string(1, "New track") } })
        assertNull(state.current()!!.artist)
        assertNull(state.current()!!.elapsedMillis)
        assertNull(state.current()!!.durationMillis)
    }
}
