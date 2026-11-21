package com.example.wiprober

/** The displayed command is an argv editor for the bundled executable. No shell is involved. */
internal object IperfCommand {
    private val plainWord = Regex("[A-Za-z0-9_./:%@=,+-]+")

    fun format(arguments: List<String>): String =
        (listOf("iperf3") + arguments).joinToString(" ") { word ->
            if (plainWord.matches(word)) word else "'" + word.replace("'", "'\\''") + "'"
        }

    /** A suffix starting at an argument boundary can survive edits to the form. */
    fun followsFields(command: String, formCommand: String): Boolean =
        command.startsWith(formCommand) &&
            (command.length == formCommand.length || command[formCommand.length].isWhitespace())

    enum class Error { QUOTING, PROGRAM, EMPTY, NUL }

    class Invalid(val reason: Error) : IllegalArgumentException()

    fun arguments(command: String): List<String> {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        var started = false
        var quote: Char? = null
        var i = 0
        if ('\u0000' in command) throw Invalid(Error.NUL)
        while (i < command.length) {
            val c = command[i++]
            when {
                quote == null && c.isWhitespace() -> {
                    if (started) {
                        words.add(word.toString())
                        word.clear()
                        started = false
                    }
                    continue
                }
                c == quote -> quote = null
                quote == null && (c == '\'' || c == '"') -> quote = c
                c == '\\' && quote != '\'' -> {
                    if (i == command.length) throw Invalid(Error.QUOTING)
                    val next = command[i]
                    if (quote == null || next in "\\\"\$`\n") {
                        i++
                        if (next == '\n') continue
                        word.append(next)
                    } else {
                        word.append(c)
                    }
                }
                else -> word.append(c)
            }
            started = true
        }
        if (quote != null) throw Invalid(Error.QUOTING)
        if (started) words.add(word.toString())
        if (words.firstOrNull() != "iperf3") throw Invalid(Error.PROGRAM)
        if (words.size == 1) throw Invalid(Error.EMPTY)
        return words.drop(1)
    }
}
