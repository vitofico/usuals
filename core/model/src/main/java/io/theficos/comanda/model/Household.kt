package io.theficos.comanda.model

import kotlinx.serialization.Serializable

/** Someone who can add to the list. Display name only: no email, no avatar, no account. */
@Serializable
data class Member(val id: MemberId, val displayName: String)

/** The group a list belongs to. */
@Serializable
data class Household(
    val id: HouseholdId,
    val name: String,
    val members: List<Member> = emptyList(),
) {
    fun member(id: MemberId): Member? = members.firstOrNull { it.id == id }
}
