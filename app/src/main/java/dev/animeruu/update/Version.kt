package dev.animeruu.update

/**
 * Versi semantik sederhana: "v1.2.3", "1.2", "1.0.0-ci.14".
 * Suffix (-ci.N, -beta) = pra-rilis → lebih rendah dari rilis bernomor sama.
 */
data class Version(val parts: List<Int>, val pre: String?) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val a = parts.getOrElse(i) { 0 }
            val b = other.parts.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return when {
            pre == other.pre -> 0
            pre == null -> 1
            other.pre == null -> -1
            else -> pre.compareTo(other.pre)
        }
    }

    override fun toString() = parts.joinToString(".") + (pre?.let { "-$it" } ?: "")

    companion object {
        private val re = Regex("""^[vV]?(\d+(?:\.\d+)*)(?:[-+](.+))?$""")
        fun parse(raw: String?): Version? {
            val m = re.matchEntire(raw?.trim().orEmpty()) ?: return null
            return Version(m.groupValues[1].split('.').map { it.toInt() }, m.groupValues[2].ifEmpty { null })
        }
    }
}
