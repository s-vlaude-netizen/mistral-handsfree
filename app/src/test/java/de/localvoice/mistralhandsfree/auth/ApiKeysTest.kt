package de.localvoice.mistralhandsfree.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeysTest {

    private val key = "AbCdEf0123456789AbCdEf0123456789"

    @Test
    fun `leaves a clean key alone`() {
        assertEquals(key, ApiKeys.normalize(key))
    }

    @Test
    fun `strips whitespace and line breaks`() {
        assertEquals(key, ApiKeys.normalize("  $key\n"))
    }

    @Test
    fun `takes the key out of an environment variable assignment`() {
        assertEquals(key, ApiKeys.normalize("MISTRAL_API_KEY=$key"))
        assertEquals(key, ApiKeys.normalize("export MISTRAL_API_KEY=\"$key\""))
    }

    @Test
    fun `takes the key out of a pasted header`() {
        assertEquals(key, ApiKeys.normalize("Authorization: Bearer $key"))
    }

    @Test
    fun `strips quotes`() {
        assertEquals(key, ApiKeys.normalize("\"$key\""))
        assertEquals(key, ApiKeys.normalize("'$key',"))
    }

    @Test
    fun `an empty paste stays empty`() {
        assertEquals("", ApiKeys.normalize(""))
        assertEquals("", ApiKeys.normalize("   \n "))
    }

    @Test
    fun `accepts something shaped like a key`() {
        assertTrue(ApiKeys.looksPlausible(key))
        assertTrue(ApiKeys.looksPlausible("sk-proj_abc.DEF-1234567890"))
    }

    @Test
    fun `rejects things that cannot be a key`() {
        assertFalse(ApiKeys.looksPlausible(""))
        assertFalse(ApiKeys.looksPlausible("short"))
        assertFalse(ApiKeys.looksPlausible("https://console.mistral.ai/api-keys"))
        assertFalse(ApiKeys.looksPlausible("this is a sentence with spaces in it"))
        assertFalse(ApiKeys.looksPlausible("a".repeat(500)))
    }

    @Test
    fun `masks everything but the last four characters`() {
        assertEquals("••••••••6789", ApiKeys.mask(key))
        assertEquals("••••••••", ApiKeys.mask("short"))
    }
}
