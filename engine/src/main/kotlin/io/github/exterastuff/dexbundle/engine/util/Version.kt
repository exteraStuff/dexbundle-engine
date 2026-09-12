package io.github.exterastuff.dexbundle.engine.util

private val VERSION_SEPARATORS = Regex("""[.\-_+]""")

/**
 * Сравнивает две версии по частям: числовые части — как числа, остальные — как строки.
 *
 * Отсутствующая часть проигрывает числу (`1.0` старее `1.0.1`) и выигрывает у всего остального
 * (`1.0` новее `1.0-alpha`).
 */
fun compareVersions(left: String, right: String): Int {
    val leftParts = left.split(VERSION_SEPARATORS)
    val rightParts = right.split(VERSION_SEPARATORS)

    for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
        val leftPart = leftParts.getOrElse(index) { "" }
        val rightPart = rightParts.getOrElse(index) { "" }

        val leftNumber = leftPart.toLongOrNull()
        val rightNumber = rightPart.toLongOrNull()

        val result =
            when {
                leftPart == rightPart -> 0

                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)

                leftPart.isEmpty() -> if (rightNumber != null) -1 else 1
                rightPart.isEmpty() -> if (leftNumber != null) 1 else -1

                else -> leftPart.compareTo(rightPart)
            }

        if (result != 0) return result
    }

    return 0
}
