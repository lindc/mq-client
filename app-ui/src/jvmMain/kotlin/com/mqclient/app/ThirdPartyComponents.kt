package com.mqclient.app


/** One row in the open-source licenses dialog (loaded from the manifest file). */
internal data class ThirdPartyComponent(
    val name: String,
    val license: String,
    val copyright: String,
)

/**
 * State machine over a small CSV dialect. `inQuotes` flips when we see the
 * matching `"`, which lets commas and newlines pass through inside a quoted
 * field; a doubled `""` inside quotes becomes a single `"`. Records whose
 * field count isn't exactly three are dropped by the caller.
 */
internal fun parseComponents(raw: String): List<ThirdPartyComponent> {
    val records = mutableListOf<List<String>>()
    val record = mutableListOf<String>()
    val field = StringBuilder()
    var inQuotes = false

    fun endField() {
        record.add(field.toString().trim())
        field.setLength(0)
    }

    fun endRecord() {
        endField()
        if (record.any { it.isNotEmpty() }) records.add(record.toList())
        record.clear()
    }

    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (inQuotes) {
            when (c) {
                '"' -> {
                    // Doubled quote inside a quoted field is a literal `"`.
                    if (i + 1 < raw.length && raw[i + 1] == '"') {
                        field.append('"')
                        i += 2
                    } else {
                        inQuotes = false
                        i++
                    }
                }
                else -> {
                    // Inside quotes we keep everything verbatim, including the
                    // newlines and commas the parser otherwise treats as
                    // delimiters.
                    field.append(c)
                    i++
                }
            }
        } else {
            when (c) {
                '"' -> {
                    inQuotes = true
                    i++
                }
                ',' -> {
                    endField()
                    i++
                }
                '\n' -> {
                    endRecord()
                    i++
                }
                '\r' -> {
                    endRecord()
                    i++
                    if (i < raw.length && raw[i] == '\n') i++
                }
                else -> {
                    field.append(c)
                    i++
                }
            }
        }
    }
    // Flush a trailing record if the file doesn't end with a newline.
    if (field.isNotEmpty() || record.isNotEmpty()) endRecord()

    return records
        .filter { it.size == 3 && it[0].isNotEmpty() }
        .map { ThirdPartyComponent(name = it[0], license = it[1], copyright = it[2]) }
}
