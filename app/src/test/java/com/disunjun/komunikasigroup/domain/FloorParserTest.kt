package com.disunjun.komunikasigroup.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FloorParserTest {

    @Test
    fun `parses full floorState payload`() {
        val payload = mapOf(
            "jenis" to "floorState",
            "state" to mapOf(
                "currentTalker" to "budi",
                "queue" to listOf("dewi", "agus"),
                "lastUpdate" to 1730000000123L
            )
        )

        val state = FloorParser.parseFloorState(payload)

        assertNotNull(state)
        assertEquals("budi", state!!.currentTalker)
        assertEquals(listOf("dewi", "agus"), state.queue)
        assertEquals(1730000000123L, state.lastUpdate)
    }

    @Test
    fun `returns null when jenis is not floorState`() {
        val payload = mapOf("jenis" to "peerEvent", "state" to emptyMap<String, Any>())
        assertNull(FloorParser.parseFloorState(payload))
    }

    @Test
    fun `tolerates null currentTalker and empty queue`() {
        val payload = mapOf(
            "jenis" to "floorState",
            "state" to mapOf(
                "currentTalker" to null,
                "queue" to emptyList<String>(),
                "lastUpdate" to 0L
            )
        )

        val state = FloorParser.parseFloorState(payload)

        assertNotNull(state)
        assertNull(state!!.currentTalker)
        assertEquals(emptyList<String>(), state.queue)
        assertEquals(0L, state.lastUpdate)
    }

    @Test
    fun `returns null when state is missing`() {
        val payload = mapOf("jenis" to "floorState")
        assertNull(FloorParser.parseFloorState(payload))
    }
}