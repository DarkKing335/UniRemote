package com.example.uniremote.network

import com.example.uniremote.data.TvBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbTextInputEncoderTest {

    @Test
    fun `space is encoded as percent-s`() {
        val commands = AdbTextInputEncoder.buildShellCommands("hello world", TvBrand.ANDROID)

        assertEquals(1, commands.size)
        assertEquals("input text hello%sworld", commands[0])
    }

    @Test
    fun `shell-sensitive characters are escaped in token path`() {
        val commands = AdbTextInputEncoder.buildShellCommands("a&b\$c", TvBrand.ANDROID)

        assertEquals(1, commands.size)
        assertEquals("input text a\\&b\\${'$'}c", commands[0])
    }

    @Test
    fun `unicode triggers quoted fallback path`() {
        val commands = AdbTextInputEncoder.buildShellCommands("go🙂", TvBrand.ANDROID)

        assertEquals(2, commands.size)
        assertEquals("input text go", commands[0])
        assertTrue(commands[1].startsWith("input text '") && commands[1].endsWith("'"))
    }

    @Test
    fun `newline splits segments and injects enter command`() {
        val commands = AdbTextInputEncoder.buildShellCommands("line1\nline2", TvBrand.ANDROID)

        assertEquals(3, commands.size)
        assertEquals("input text line1", commands[0])
        assertEquals("input keyevent 66 || input keyevent 160", commands[1])
        assertEquals("input text line2", commands[2])
    }

    @Test
    fun `carriage-return newline is normalized`() {
        val commands = AdbTextInputEncoder.buildShellCommands("A\r\nB\rC", TvBrand.ANDROID)

        assertEquals(5, commands.size)
        assertEquals("input text A", commands[0])
        assertEquals("input keyevent 66 || input keyevent 160", commands[1])
        assertEquals("input text B", commands[2])
        assertEquals("input keyevent 66 || input keyevent 160", commands[3])
        assertEquals("input text C", commands[4])
    }
}
