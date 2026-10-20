package io.theficos.usuals.model

/**
 * Version of the normalization rules in `:core:parse`.
 *
 * Purchase history is grouped by normalized name, so a rule change regroups it.
 * Every purchase row stores the name it was bought under as well as its key, and
 * the version it was keyed with, so a bump is a rebuild of keys from names rather
 * than a guess. Bump it in the same commit that changes a rule.
 */
const val NORMALIZER_VERSION: Int = 1

/**
 * Version of the prompts the model features use. Anything a model produced is
 * stamped with the prompt that produced it, so a prompt edit does not leave old
 * answers looking current.
 */
const val PROMPT_VERSION: Int = 3
