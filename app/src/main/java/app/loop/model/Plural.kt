package app.loop.model

/** "1 day", "3 days": a count with its noun in the right number. [many] covers irregular plurals. */
fun plural(n: Number, word: String, many: String = word + "s"): String = "$n ${if (n.toDouble() == 1.0) word else many}"
