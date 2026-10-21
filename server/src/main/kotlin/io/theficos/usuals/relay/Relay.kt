package io.theficos.usuals.relay

/** What every route shares: the database, the server identity, rate limits and the key that seals stored lists. */
class Relay(val db: Database, val identity: Identity, val auth: Auth, internal val cipher: ListCipher)
