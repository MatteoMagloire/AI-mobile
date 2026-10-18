package com.matteomagloire.aimobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    @Test
    fun v01FilesAreStillUsed() {
        // Déjà sur le téléphone : ni l'identifiant ni le nom du fichier ne doivent changer.
        assertEquals("ministral-3-3b-instruct-q4km", ModelCatalog.INSTRUCT.id)
        assertEquals("Ministral-3-3B-Instruct-2512-Q4_K_M.gguf", ModelCatalog.INSTRUCT.fileName)
        assertEquals("ministral-3-3b-reasoning-q4km", ModelCatalog.REASONING.id)
        assertEquals("Ministral-3-3B-Reasoning-2512-Q4_K_M.gguf", ModelCatalog.REASONING.fileName)
    }

    @Test
    fun threeFilesPerModel() {
        for (reasoning in listOf(false, true)) {
            val variants = ModelCatalog.variants(reasoning)
            assertEquals(listOf("Q4_K_M", "UD-Q4_K_XL", "Q4_0"), variants.map { it.quant })
            assertTrue(variants.all { it.reasoning == reasoning })
        }
    }

    @Test
    fun idsAndFilesAreUnique() {
        assertEquals(6, ModelCatalog.all.map { it.id }.toSet().size)
        assertEquals(6, ModelCatalog.all.map { it.fileName }.toSet().size)
        ModelCatalog.all.forEach { assertEquals(it, ModelCatalog.byId(it.id)) }
    }

    @Test
    fun urlsPointToTheFile() {
        ModelCatalog.all.forEach {
            assertTrue(it.url.startsWith("https://huggingface.co/"))
            assertTrue(it.url.endsWith("/resolve/main/${it.fileName}"))
        }
    }
}
