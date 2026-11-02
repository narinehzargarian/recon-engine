package io.github.narinehzargarian.recon;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Matching rules.
 *
 * @param amountToleranceMinor largest allowed amount difference for one-to-one matches, in minor units
 * @param dateWindowDays largest allowed date difference for one-to-one matches
 * @param minDescriptionSimilarity 0 to 1; pairs below this are never matched (0 disables the check)
 * @param groupMatching whether to match one transaction against several on the other side
 * @param maxGroupSize largest number of transactions combined into one group match
 * @param groupWindowDays how far before the single transaction a group's members may fall
 * @param groupToleranceMinor largest allowed difference for group matches, after fees. Keep this at 0
 *     unless your processor rounds differently: with many possible combinations, a tolerance lets
 *     wrong combinations through, and each wrong group takes transactions from the next.
 * @param feePercent fee taken from each grouped transaction, as a percentage (2.9 means 2.9%)
 * @param feeFixedMinor fixed fee taken from each grouped transaction, in minor units
 * @param optimalComponentLimit largest cluster solved optimally; bigger ones use a greedy pass
 */
public record MatchConfig(
        long amountToleranceMinor,
        int dateWindowDays,
        double minDescriptionSimilarity,
        boolean groupMatching,
        int maxGroupSize,
        int groupWindowDays,
        long groupToleranceMinor,
        BigDecimal feePercent,
        long feeFixedMinor,
        int optimalComponentLimit) {

    public MatchConfig {
        require(amountToleranceMinor >= 0, "amountToleranceMinor must not be negative");
        require(dateWindowDays >= 0, "dateWindowDays must not be negative");
        require(minDescriptionSimilarity >= 0 && minDescriptionSimilarity <= 1, "minDescriptionSimilarity must be between 0 and 1");
        require(maxGroupSize >= 2 && maxGroupSize <= 12, "maxGroupSize must be between 2 and 12");
        require(groupWindowDays >= 0, "groupWindowDays must not be negative");
        require(groupToleranceMinor >= 0, "groupToleranceMinor must not be negative");
        feePercent = feePercent == null ? BigDecimal.ZERO : feePercent;
        require(feePercent.signum() >= 0 && feePercent.compareTo(BigDecimal.valueOf(50)) < 0, "feePercent must be between 0 and 50");
        require(feeFixedMinor >= 0, "feeFixedMinor must not be negative");
        optimalComponentLimit = optimalComponentLimit == 0 ? 300 : optimalComponentLimit;
        require(optimalComponentLimit >= 1 && optimalComponentLimit <= 2000, "optimalComponentLimit must be between 1 and 2000");
    }

    public static MatchConfig defaults() {
        return new MatchConfig(0, 3, 0, true, 6, 7, 0, BigDecimal.ZERO, 0, 300);
    }

    public MatchConfig withAmountTolerance(long minor) {
        return new MatchConfig(minor, dateWindowDays, minDescriptionSimilarity, groupMatching, maxGroupSize, groupWindowDays, groupToleranceMinor, feePercent, feeFixedMinor, optimalComponentLimit);
    }

    public MatchConfig withDateWindow(int days) {
        return new MatchConfig(amountToleranceMinor, days, minDescriptionSimilarity, groupMatching, maxGroupSize, groupWindowDays, groupToleranceMinor, feePercent, feeFixedMinor, optimalComponentLimit);
    }

    public MatchConfig withMinDescriptionSimilarity(double value) {
        return new MatchConfig(amountToleranceMinor, dateWindowDays, value, groupMatching, maxGroupSize, groupWindowDays, groupToleranceMinor, feePercent, feeFixedMinor, optimalComponentLimit);
    }

    public MatchConfig withGroupMatching(boolean enabled, int maxSize, int windowDays) {
        return new MatchConfig(amountToleranceMinor, dateWindowDays, minDescriptionSimilarity, enabled, maxSize, windowDays, groupToleranceMinor, feePercent, feeFixedMinor, optimalComponentLimit);
    }

    public MatchConfig withGroupTolerance(long minor) {
        return new MatchConfig(amountToleranceMinor, dateWindowDays, minDescriptionSimilarity, groupMatching, maxGroupSize, groupWindowDays, minor, feePercent, feeFixedMinor, optimalComponentLimit);
    }

    public MatchConfig withFees(BigDecimal percent, long fixedMinor) {
        return new MatchConfig(amountToleranceMinor, dateWindowDays, minDescriptionSimilarity, groupMatching, maxGroupSize, groupWindowDays, groupToleranceMinor, percent, fixedMinor, optimalComponentLimit);
    }

    public MatchConfig withOptimalComponentLimit(int limit) {
        return new MatchConfig(amountToleranceMinor, dateWindowDays, minDescriptionSimilarity, groupMatching, maxGroupSize, groupWindowDays, groupToleranceMinor, feePercent, feeFixedMinor, limit);
    }

    /** The fee on one amount, rounded half-even to a whole minor unit. Applied to the absolute value. */
    public long feeFor(long amountMinor) {
        if (feePercent.signum() == 0 && feeFixedMinor == 0) {
            return 0;
        }
        long percentPart = BigDecimal.valueOf(Math.abs(amountMinor)).multiply(feePercent)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_EVEN).longValueExact();
        return percentPart + feeFixedMinor;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
