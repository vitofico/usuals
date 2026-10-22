package io.theficos.usuals.model

/** [SOURCE_KEPT] means a copy was attempted but removing the original could not be proved safe. */
enum class ItemTransferResult { COPIED, MOVED, SOURCE_CHANGED, DESTINATION_UNAVAILABLE, SOURCE_KEPT }
