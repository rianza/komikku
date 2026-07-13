package tachiyomi.core.common.util.lang

import java.text.Collator
import java.util.Locale

private val collator = object : ThreadLocal<Collator>() {
    override fun initialValue(): Collator {
        return Collator.getInstance(Locale.getDefault()).apply {
            strength = Collator.PRIMARY
        }
    }
}

fun String.compareToWithCollator(other: String): Int {
    return collator.get()!!.compare(this, other)
}
