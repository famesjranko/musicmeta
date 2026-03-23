package com.landofoz.musicmeta.demo.ui

import java.io.BufferedReader
import java.io.InputStreamReader

/** Theme-aware terminal output. All CLI rendering goes through this class. */
class Terminal(val theme: Theme) {

    private val reader = BufferedReader(InputStreamReader(System.`in`))

    fun styled(text: String, vararg codes: String): String =
        if (codes.isEmpty()) text
        else "${codes.joinToString("")}$text${theme.reset}"

    fun print(text: String) = kotlin.io.print(text)
    fun println(text: String = "") = kotlin.io.println(text)

    fun banner(title: String) {
        val inner = "  $title"
        val width = maxOf(inner.length + 2, 60)
        val pad = width - inner.length - 2
        println(styled(
            "${theme.boxTL}${theme.boxH.repeat(width)}${theme.boxTR}\n" +
                "${theme.boxV}$inner${" ".repeat(pad)}${theme.boxV}\n" +
                "${theme.boxBL}${theme.boxH.repeat(width)}${theme.boxBR}",
            theme.primary, theme.bold,
        ))
    }

    fun heading(title: String) {
        val bar = theme.headingBar.repeat(maxOf(58 - title.length, 4))
        println(styled("${theme.headingBar.repeat(2)} $title $bar", theme.primary, theme.bold))
    }

    fun success(label: String, detail: String) =
        println("  ${styled(theme.check, theme.success)} ${styled(label.padEnd(20), theme.bold)} $detail")

    fun missing(label: String, detail: String) =
        println("  ${styled(theme.dot, theme.muted)} ${styled(label.padEnd(20), theme.muted)} ${styled(detail, theme.muted)}")

    fun error(label: String, detail: String) =
        println("  ${styled(theme.cross, theme.error)} ${styled(label.padEnd(20), theme.bold)} ${styled(detail, theme.error)}")

    fun warning(label: String, detail: String) =
        println("  ${styled(theme.warn, theme.warning)} ${styled(label.padEnd(20), theme.bold)} ${styled(detail, theme.warning)}")

    fun info(text: String) = println(styled("  $text", theme.muted))

    fun keyValue(key: String, value: String) =
        println("  ${styled(key.padEnd(12), theme.accent)}$value")

    fun summary(found: Int, skipped: Int, errors: Int) {
        val parts = mutableListOf(styled("$found found", theme.success))
        if (skipped > 0) parts += styled("$skipped skipped", theme.muted)
        if (errors > 0) parts += styled("$errors errors", theme.error)
        println("\n  ${parts.joinToString(styled(" ${theme.dot} ", theme.muted))}")
    }

    fun providerRow(name: String, active: Boolean) {
        val symbol = if (active) styled(theme.bullet, theme.success) else styled(theme.warn, theme.warning)
        val status = if (active) styled("ACTIVE", theme.success) else styled("NO KEY", theme.warning)
        print("  $symbol ${name.padEnd(16)} ${status.padEnd(20)}")
    }

    fun prompt(): String? {
        print(styled("musicmeta", theme.primary, theme.bold) + styled("${theme.arrow} ", theme.muted))
        System.out.flush()
        return reader.readLine()
    }

    /** Clear the current line (for spinner cleanup). */
    fun clearLine() = print("\r${" ".repeat(72)}\r")
}
