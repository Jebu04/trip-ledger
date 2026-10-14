package app.tripledger.domain

import java.util.Locale

/**
 * Search criteria. Text is matched case-insensitively against title, note and category name;
 * several words must all match. Amount bounds are in minor units of the value being compared
 * (the home-currency value when one exists, otherwise the original amount).
 */
data class SearchFilter(
    val query: String = "",
    val categoryIds: Set<Long> = emptySet(),
    val fromDate: Long? = null,
    val toDate: Long? = null,
    val minMinor: Long? = null,
    val maxMinor: Long? = null,
) {
    val isEmpty: Boolean
        get() = query.isBlank() && categoryIds.isEmpty() && fromDate == null && toDate == null &&
            minMinor == null && maxMinor == null

    fun matches(item: ConvertedEntry, categoryName: String): Boolean {
        val e = item.entry
        if (categoryIds.isNotEmpty() && e.categoryId !in categoryIds) return false
        if (fromDate != null && e.date < fromDate) return false
        if (toDate != null && e.date > toDate) return false
        val amount = item.homeMinor ?: e.amountMinor
        if (minMinor != null && amount < minMinor) return false
        if (maxMinor != null && amount > maxMinor) return false
        val tokens = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true
        val haystack = "${e.title}\n${e.note}\n$categoryName".lowercase(Locale.ROOT)
        return tokens.all { haystack.contains(it) }
    }
}
