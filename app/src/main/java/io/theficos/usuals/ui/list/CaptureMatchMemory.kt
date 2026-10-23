package io.theficos.usuals.ui.list

/** Declined corrections are local and scoped to the list. Positive choices use its confirmed aliases. */
interface CaptureMatchMemory {
    fun declined(scope: String, from: String, to: String): Boolean
    fun decline(scope: String, from: String, to: String)
}

class InMemoryCaptureMatchMemory : CaptureMatchMemory {
    private val declined = mutableSetOf<Triple<String, String, String>>()
    override fun declined(scope: String, from: String, to: String) = Triple(scope, from, to) in declined
    override fun decline(scope: String, from: String, to: String) { declined += Triple(scope, from, to) }
}
