package io.theficos.usuals.model

import kotlinx.serialization.Serializable

/**
 * Identity wrappers.
 *
 * Every one of these is a string underneath, and at a call site an `ItemId`
 * and a `MemberId` look identical. Wrapping them lets the compiler catch a
 * swapped pair, and a value class is erased to its underlying string at
 * runtime, so the check costs nothing.
 */
@Serializable
@JvmInline
value class ItemId(val value: String) {
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class MemberId(val value: String) {
    override fun toString(): String = value
}
