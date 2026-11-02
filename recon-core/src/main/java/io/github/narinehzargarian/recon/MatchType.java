package io.github.narinehzargarian.recon;

public enum MatchType {
    /** Both sides carry the same reference, such as an invoice number. */
    REFERENCE,
    /** Same amount, same date. */
    EXACT,
    /** Within the amount tolerance and date window, but not identical. */
    FUZZY,
    /** One transaction matches several on the other side, such as a payout covering many charges. */
    GROUP
}
