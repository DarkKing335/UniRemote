package com.example.uniremote.network

import com.example.uniremote.data.TvBrand

internal object AdbTextInputEncoder {

    fun buildShellCommands(text: String, brand: TvBrand): List<String> {
        if (text.isBlank()) return emptyList()

        val commands = mutableListOf<String>()
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val encodedBuffer = StringBuilder()

        fun flushEncodedBuffer() {
            if (encodedBuffer.isNotEmpty()) {
                commands += "input text $encodedBuffer"
                encodedBuffer.setLength(0)
            }
        }

        var index = 0
        while (index < normalized.length) {
            val codePoint = normalized.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            val chunk = normalized.substring(index, index + charCount)

            if (chunk == "\n") {
                flushEncodedBuffer()
                commands += enterCommandForBrand(brand)
            } else if (charCount == 1 && canEncodeInAdbPath(chunk[0])) {
                encodedBuffer.append(encodeCharForAdb(chunk[0]))
            } else {
                flushEncodedBuffer()
                val escaped = escapeForSingleQuotedShell(chunk)
                commands += "input text '$escaped'"
            }

            index += charCount
        }

        flushEncodedBuffer()
        return commands
    }

    fun enterCommandForBrand(brand: TvBrand): String {
        return if (brand in setOf(TvBrand.ANDROID, TvBrand.GOOGLE_TV, TvBrand.XIAOMI, TvBrand.SONY, TvBrand.FIRE_TV)) {
            "input keyevent 66 || input keyevent 160"
        } else {
            "input keyevent 66"
        }
    }

    fun canEncodeInAdbPath(ch: Char): Boolean {
        if (ch == '%') return false
        if (ch.code !in 0x20..0x7E) return false

        return ch.isLetterOrDigit() || ch == ' ' || ch in setOf(
            '.', '_', '-', '@', '/', ':', ',', '=', '+', '#', '?',
            '!', '~', '^', '(', ')', '[', ']', '{', '}', '*',
            ';', '|', '<', '>', '$', '&', '"', '\'', '\\'
        )
    }

    fun encodeCharForAdb(ch: Char): String = when (ch) {
        ' ' -> "%s"
        '"', '\'', '\\', '$', '&', ';', '|', '<', '>', '(', ')', '[', ']', '{', '}', '*', '?', '!' -> "\\$ch"
        else -> ch.toString()
    }

    fun escapeForSingleQuotedShell(raw: String): String {
        return raw.replace("'", "'\\''")
    }
}
