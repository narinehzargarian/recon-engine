package io.github.narinehzargarian.recon;

import java.util.List;

/**
 * Transactions judged to be the same money movement.
 *
 * @param type how the match was found
 * @param currency the currency of every transaction in the match
 * @param leftIds transactions from the left source
 * @param rightIds transactions from the right source
 * @param amountDifferenceMinor right total minus the expected amount (left total, less any fees)
 * @param feesMinor fees accounted for in a group match
 * @param maxDateDifferenceDays largest date gap between any two matched transactions
 * @param descriptionSimilarity 0 to 1, for one-to-one matches
 * @param ambiguous whether another, equally good match existed; worth a human look
 */
public record Match(
        MatchType type,
        String currency,
        List<String> leftIds,
        List<String> rightIds,
        long amountDifferenceMinor,
        long feesMinor,
        int maxDateDifferenceDays,
        double descriptionSimilarity,
        boolean ambiguous) {

    public Match {
        leftIds = List.copyOf(leftIds);
        rightIds = List.copyOf(rightIds);
    }
}
