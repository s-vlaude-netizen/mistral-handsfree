package de.localvoice.mistralhandsfree.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandsTest {

    @Test
    fun `recognizes stop commands regardless of case and punctuation`() {
        assertTrue(VoiceCommands.isStopCommand("Stop"))
        assertTrue(VoiceCommands.isStopCommand("  goodbye. "))
        assertTrue(VoiceCommands.isStopCommand("Gespräch beenden"))
        assertTrue(VoiceCommands.isStopCommand("Tschüss!"))
        assertTrue(VoiceCommands.isStopCommand("Au revoir"))
        assertTrue(VoiceCommands.isStopCommand("Arrête"))
    }

    @Test
    fun `politeness around a command does not matter`() {
        assertTrue(VoiceCommands.isStopCommand("okay, stop"))
        assertTrue(VoiceCommands.isStopCommand("Thanks, bye"))
        assertTrue(VoiceCommands.isStopCommand("danke, tschüss"))
        assertTrue(VoiceCommands.isStopCommand("stop please"))
    }

    @Test
    fun `typographic apostrophes are understood`() {
        assertTrue(VoiceCommands.isStopCommand("that’s all"))
    }

    @Test
    fun `a sentence that merely contains the word is not a command`() {
        assertFalse(VoiceCommands.isStopCommand("Explain what an emergency stop is"))
        assertFalse(VoiceCommands.isStopCommand("stop explaining that and start over"))
        assertFalse(VoiceCommands.isStopCommand("Why did the bus stop"))
    }

    @Test
    fun `nothing and mere politeness are not commands`() {
        assertFalse(VoiceCommands.isStopCommand(""))
        assertFalse(VoiceCommands.isStopCommand("   "))
        assertFalse(VoiceCommands.isStopCommand("thank you"))
        assertFalse(VoiceCommands.isStopCommand("okay"))
    }
}
