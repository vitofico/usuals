package io.theficos.usuals.enrich

import io.theficos.usuals.model.Gtin

/**
 * The one lookup we decline to make.
 *
 * GS1 does not hand every number range to manufacturers. Prefixes 02 and 04, and
 * the whole 20 to 29 band, are reserved for *restricted circulation*: numbers
 * that mean something only inside the shop that printed them. In a supermarket
 * that is almost always a variable-weight item, the barcode a scale prints at the
 * deli counter with the weight and the price baked into the digits. The same
 * number is a different cheese in a different chain, so no global database can
 * resolve it. Asking Open Food Facts about one spends a request that cannot
 * succeed, which is why these become [SkipReason.RESTRICTED_CIRCULATION] instead
 * of a real request that comes back [QualityLookup.Missing].
 *
 * This is a conservative skip policy, not a proof that no record exists. Nothing
 * stops a restricted code from coincidentally having an OFF entry, and we decline
 * to spend a request finding out. The trade is deliberate and asymmetric: a wrong
 * skip costs one product shown without quality facts, which is exactly the state
 * of the many products OFF has never heard of, while not skipping spends a scarce
 * request budget on codes that are structurally unresolvable.
 *
 * ### What this check can and cannot see
 *
 * [Gtin] normalises every barcode to thirteen digits by left-padding with zeros,
 * and that padding decides what is visible here.
 *
 * A **UPC-A** beginning with 2 is the North American in-store range, and it is
 * caught correctly: twelve digits gain one leading zero, so `212345002506`
 * arrives as `0212345002506` and reads as prefix 02. That is a coincidence of the
 * padding rather than something this object reasons about, so a test pins it.
 *
 * An **EAN-8** is not caught, and cannot be from the canonical form. It gains
 * five zeros, so its own leading digits sit at positions 5 and 6 while positions
 * 0 and 1 read "00" for every short code in existence. A restricted EAN-8 looks
 * ordinary here, gets looked up, and comes back missing at the cost of one
 * request. Detecting it would mean knowing the code arrived as eight digits, and
 * [Gtin] deliberately does not keep the original length: one canonical stored form
 * is the whole point of that type. Guessing instead, by reading positions 5 and 6,
 * would misclassify every ordinary EAN-13 whose sixth and seventh digits happen to
 * land in the band, trading a rare wasted request for silently skipping real
 * products. A test pins the current behaviour so that a later "fix" that looks at
 * the wrong digits fails loudly rather than shipping.
 */
object RestrictedCirculation {

    /**
     * The two-digit prefixes GS1 reserves for in-store numbering.
     *
     * Held as strings rather than as integer ranges because the canonical form is
     * a string and its leading zero is significant: "02" and "2" are different
     * prefixes, and comparing them as numbers loses exactly that distinction.
     */
    private val RESTRICTED_PREFIXES: Set<String> = buildSet {
        add("02")
        add("04")
        for (prefix in 20..29) add(prefix.toString())
    }

    /**
     * Whether [gtin] is in a restricted-circulation range and should be skipped
     * rather than looked up.
     *
     * Reads the first two digits of the canonical thirteen-digit form. See the
     * class documentation for what that does and does not catch, in particular
     * that a restricted EAN-8 returns false here.
     */
    fun isRestricted(gtin: Gtin): Boolean = gtin.value.take(2) in RESTRICTED_PREFIXES
}
