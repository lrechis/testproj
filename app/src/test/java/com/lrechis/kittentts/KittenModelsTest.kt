package com.lrechis.kittentts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KittenModelsTest {

    @Test
    fun modelIdsAreUniqueAndWellFormed() {
        val ids = KittenModels.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val pattern = Regex("^kitten-(nano|micro|mini)-en-v\\d_\\d(-(fp16|fp32|int8))?$")
        for (id in ids) {
            assertTrue("unexpected id $id", pattern.matches(id))
        }
    }

    @Test
    fun archiveUrlPointsAtSherpaOnnxRelease() {
        val model = KittenModels.byId("kitten-nano-en-v0_8-int8")
        assertEquals(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kitten-nano-en-v0_8-int8.tar.bz2",
            model.archiveUrl,
        )
    }

    @Test
    fun defaultModelIsTheSmallestAndInTheList() {
        assertTrue(KittenModels.ALL.contains(KittenModels.DEFAULT))
        assertEquals("kitten-nano-en-v0_8-int8", KittenModels.DEFAULT.id)
    }

    @Test
    fun unknownIdFallsBackToDefault() {
        assertSame(KittenModels.DEFAULT, KittenModels.byId("does-not-exist"))
        assertSame(KittenModels.DEFAULT, KittenModels.byId(null))
    }

    @Test
    fun eightVoicesWithGenderLabels() {
        assertEquals(8, KittenModels.VOICES.size)
        assertEquals("expr-voice-2-m (male)", KittenModels.voiceLabel(0))
        assertEquals("expr-voice-5-f (female)", KittenModels.voiceLabel(7))
    }
}
