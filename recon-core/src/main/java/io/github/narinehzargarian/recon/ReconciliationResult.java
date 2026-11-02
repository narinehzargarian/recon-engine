package io.github.narinehzargarian.recon;

import java.util.List;
import java.util.Map;

/**
 * Everything the engine found.
 *
 * @param possibleDuplicatesLeft groups of left transactions with the same date, amount, and description
 * @param possibleDuplicatesRight the same, for the right side
 */
public record ReconciliationResult(
        List<Match> matches,
        List<Transaction> unmatchedLeft,
        List<Transaction> unmatchedRight,
        List<List<String>> possibleDuplicatesLeft,
        List<List<String>> possibleDuplicatesRight,
        Summary summary) {

    public ReconciliationResult {
        matches = List.copyOf(matches);
        unmatchedLeft = List.copyOf(unmatchedLeft);
        unmatchedRight = List.copyOf(unmatchedRight);
        possibleDuplicatesLeft = List.copyOf(possibleDuplicatesLeft);
        possibleDuplicatesRight = List.copyOf(possibleDuplicatesRight);
    }

    /**
     * @param groupSearchLimitHits group searches stopped early because there were too many combinations
     */
    public record Summary(
            int leftCount,
            int rightCount,
            int matchedLeft,
            int matchedRight,
            long unmatchedLeftTotalMinor,
            long unmatchedRightTotalMinor,
            Map<MatchType, Integer> matchesByType,
            int ambiguousMatches,
            int groupSearchLimitHits) {

        public Summary {
            matchesByType = Map.copyOf(matchesByType);
        }
    }
}
