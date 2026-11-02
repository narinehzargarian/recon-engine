package io.github.narinehzargarian.recon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReconcilerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 2);

    private static Transaction tx(String id, int dayOffset, long cents, String description) {
        return Transaction.of(id, DAY.plusDays(dayOffset), cents, description);
    }

    private static Transaction tx(String id, int dayOffset, long cents, String description, String reference) {
        return new Transaction(id, DAY.plusDays(dayOffset), cents, "USD", description, reference);
    }

    private static ReconciliationResult run(MatchConfig config, List<Transaction> left, List<Transaction> right) {
        return new Reconciler(config).reconcile(left, right);
    }

    @Test
    void identicalTransactionsMatchExactly() {
        var result = run(MatchConfig.defaults(), List.of(tx("L1", 0, 1999, "Coffee")), List.of(tx("R1", 0, 1999, "COFFEE")));
        assertThat(result.matches()).singleElement().satisfies(match -> {
            assertThat(match.type()).isEqualTo(MatchType.EXACT);
            assertThat(match.leftIds()).containsExactly("L1");
            assertThat(match.rightIds()).containsExactly("R1");
        });
        assertThat(result.unmatchedLeft()).isEmpty();
        assertThat(result.unmatchedRight()).isEmpty();
    }

    @Test
    void dateWindowIsInclusiveAndEnforced() {
        var config = MatchConfig.defaults().withDateWindow(3);
        assertThat(run(config, List.of(tx("L1", 0, 500, "a")), List.of(tx("R1", 3, 500, "a"))).matches()).hasSize(1);
        assertThat(run(config, List.of(tx("L1", 0, 500, "a")), List.of(tx("R1", 4, 500, "a"))).matches()).isEmpty();
        assertThat(run(config, List.of(tx("L1", 0, 500, "a")), List.of(tx("R1", -3, 500, "a"))).matches()).hasSize(1);
    }

    @Test
    void amountToleranceIsInclusiveAndEnforced() {
        var config = MatchConfig.defaults().withAmountTolerance(25);
        var within = run(config, List.of(tx("L1", 0, 10000, "a")), List.of(tx("R1", 0, 9975, "a")));
        assertThat(within.matches()).singleElement().satisfies(match -> {
            assertThat(match.type()).isEqualTo(MatchType.FUZZY);
            assertThat(match.amountDifferenceMinor()).isEqualTo(-25);
        });
        assertThat(run(config, List.of(tx("L1", 0, 10000, "a")), List.of(tx("R1", 0, 9974, "a"))).matches()).isEmpty();
    }

    @Test
    void zeroToleranceMeansExactAmountsOnly() {
        assertThat(run(MatchConfig.defaults(), List.of(tx("L1", 0, 1000, "a")), List.of(tx("R1", 0, 1001, "a"))).matches()).isEmpty();
    }

    @Test
    void signsMustAgree() {
        // A $10 refund is not a $10 charge.
        assertThat(run(MatchConfig.defaults(), List.of(tx("L1", 0, -1000, "a")), List.of(tx("R1", 0, 1000, "a"))).matches()).isEmpty();
    }

    @Test
    void currenciesNeverMatchEachOther() {
        var left = List.of(new Transaction("L1", DAY, 1000, "USD", "x", null));
        var right = List.of(new Transaction("R1", DAY, 1000, "EUR", "x", null));
        assertThat(run(MatchConfig.defaults(), left, right).matches()).isEmpty();
    }

    @Test
    void sharedReferenceMatchesBeforeAnythingElse() {
        // R2 is an exact match on amount and date, but R1 carries the same invoice number.
        var left = List.of(tx("L1", 0, 5000, "Invoice", "INV-7"));
        var right = List.of(tx("R1", 2, 5000, "wire", "inv-7"), tx("R2", 0, 5000, "Invoice"));
        var result = run(MatchConfig.defaults(), left, right);
        assertThat(result.matches()).singleElement().satisfies(match -> {
            assertThat(match.type()).isEqualTo(MatchType.REFERENCE);
            assertThat(match.rightIds()).containsExactly("R1");
        });
    }

    @Test
    void sharedReferenceAloneIsNotEnough() {
        // Same invoice number, but a very different amount: a partial payment, not a match.
        var result = run(MatchConfig.defaults(), List.of(tx("L1", 0, 5000, "a", "INV-7")), List.of(tx("R1", 0, 2000, "a", "INV-7")));
        assertThat(result.matches()).isEmpty();
    }

    @Test
    void optimalAssignmentMatchesMoreThanGreedyWould() {
        // Greedy pairs L1 with its cheapest option, R1, and leaves L2 with nothing. The optimal
        // assignment gives L1 the slightly worse R2, so both are matched.
        var config = MatchConfig.defaults().withDateWindow(3);
        var left = List.of(tx("L1", 0, 1000, "rent"), tx("L2", 3, 1000, "rent"));
        var right = List.of(tx("R1", 1, 1000, "rent"), tx("R2", -1, 1000, "rent"));
        var optimal = run(config, left, right);
        assertThat(optimal.matches()).hasSize(2);

        var greedy = run(config.withOptimalComponentLimit(1), left, right);
        assertThat(greedy.matches()).hasSize(1);
    }

    @Test
    void closerCandidateWinsWhenBothAreValid() {
        var config = MatchConfig.defaults().withDateWindow(5);
        var result = run(config, List.of(tx("L1", 0, 1000, "Electric bill")), List.of(tx("R1", 4, 1000, "ELECTRIC BILL"), tx("R2", 1, 1000, "ELECTRIC BILL")));
        assertThat(result.matches()).singleElement().satisfies(match -> assertThat(match.rightIds()).containsExactly("R2"));
        assertThat(result.unmatchedRight()).extracting(Transaction::id).containsExactly("R1");
    }

    @Test
    void descriptionBreaksTiesBetweenOtherwiseEqualCandidates() {
        var result = run(MatchConfig.defaults(), List.of(tx("L1", 0, 4200, "Payment to Juniper Cloud")),
                List.of(tx("R1", 0, 4200, "ORCHID DESIGN 1234"), tx("R2", 0, 4200, "JUNIPER CLOUD 99831")));
        assertThat(result.matches()).singleElement().satisfies(match -> {
            assertThat(match.rightIds()).containsExactly("R2");
            assertThat(match.ambiguous()).isFalse();
        });
    }

    @Test
    void identicalCandidatesAreFlaggedAmbiguous() {
        var result = run(MatchConfig.defaults(), List.of(tx("L1", 0, 4200, "fee")), List.of(tx("R1", 0, 4200, "fee"), tx("R2", 0, 4200, "fee")));
        assertThat(result.matches()).singleElement().satisfies(match -> assertThat(match.ambiguous()).isTrue());
    }

    @Test
    void minimumDescriptionSimilarityFiltersOutUnrelatedPairs() {
        var config = MatchConfig.defaults().withMinDescriptionSimilarity(0.3);
        assertThat(run(config, List.of(tx("L1", 0, 900, "Acme Office Supply")), List.of(tx("R1", 0, 900, "TOTALLY UNRELATED"))).matches()).isEmpty();
        assertThat(run(config, List.of(tx("L1", 0, 900, "Acme Office Supply")), List.of(tx("R1", 0, 900, "ACME OFFICE SUP 5531"))).matches()).hasSize(1);
    }

    @Test
    void payoutMatchesTheSalesItCovers() {
        var config = MatchConfig.defaults().withGroupMatching(true, 6, 7);
        var sales = List.of(tx("S1", 0, 2000, "sale"), tx("S2", 1, 3500, "sale"), tx("S3", 1, 1250, "sale"), tx("S4", 2, 999, "unrelated sale"));
        var payout = List.of(tx("P1", 3, 6750, "PAYOUT"));
        var result = run(config, sales, payout);
        assertThat(result.matches()).singleElement().satisfies(match -> {
            assertThat(match.type()).isEqualTo(MatchType.GROUP);
            assertThat(match.leftIds()).containsExactlyInAnyOrder("S1", "S2", "S3");
            assertThat(match.amountDifferenceMinor()).isZero();
        });
        assertThat(result.unmatchedLeft()).extracting(Transaction::id).containsExactly("S4");
    }

    @Test
    void payoutNetOfFeesMatches() {
        var config = MatchConfig.defaults().withFees(new BigDecimal("2.9"), 30);
        // Fees: 2000 -> 58 + 30, 3000 -> 87 + 30. Net = 5000 - 205 = 4795.
        var result = run(config, List.of(tx("S1", 0, 2000, "sale"), tx("S2", 0, 3000, "sale")), List.of(tx("P1", 2, 4795, "PAYOUT")));
        assertThat(result.matches()).singleElement().satisfies(match -> {
            assertThat(match.type()).isEqualTo(MatchType.GROUP);
            assertThat(match.feesMinor()).isEqualTo(205);
            assertThat(match.amountDifferenceMinor()).isZero();
        });
    }

    @Test
    void groupsMustBeExactUnlessAGroupToleranceIsSet() {
        var left = List.of(tx("S1", 0, 2000, "sale"), tx("S2", 0, 3000, "sale"));
        var right = List.of(tx("P1", 1, 4998, "PAYOUT"));
        // The one-to-one tolerance doesn't loosen group matching.
        assertThat(run(MatchConfig.defaults().withAmountTolerance(50), left, right).matches()).isEmpty();
        assertThat(run(MatchConfig.defaults().withGroupTolerance(2), left, right).matches()).singleElement()
                .satisfies(match -> assertThat(match.amountDifferenceMinor()).isEqualTo(-2));
    }

    @Test
    void groupMatchingWorksInTheOtherDirection() {
        // One ledger entry recorded as one bill, paid by the bank in two transfers.
        var result = run(MatchConfig.defaults(), List.of(tx("L1", 5, -10000, "Contractor")), List.of(tx("R1", 0, -6000, "x"), tx("R2", 3, -4000, "y")));
        assertThat(result.matches()).singleElement().satisfies(match -> {
            assertThat(match.leftIds()).containsExactly("L1");
            assertThat(match.rightIds()).containsExactlyInAnyOrder("R1", "R2");
        });
    }

    @Test
    void groupPrefersFewerTransactions() {
        var config = MatchConfig.defaults();
        var result = run(config, List.of(tx("A", 0, 5000, "x"), tx("B", 0, 5000, "x"), tx("C", 0, 2500, "x"), tx("D", 0, 2500, "x"),
                tx("E", 0, 2500, "x"), tx("F", 0, 2500, "x")), List.of(tx("P", 1, 10000, "payout")));
        assertThat(result.matches()).singleElement().satisfies(match -> {
            assertThat(match.leftIds()).hasSize(2);
            assertThat(match.ambiguous()).isTrue();  // Other combinations also add up.
        });
    }

    @Test
    void groupMembersMustBeBeforeThePayout() {
        var result = run(MatchConfig.defaults().withDateWindow(0), List.of(tx("S1", 5, 2000, "sale"), tx("S2", 6, 3000, "sale")), List.of(tx("P1", 0, 5000, "PAYOUT")));
        assertThat(result.matches()).isEmpty();
    }

    @Test
    void groupMatchingCanBeTurnedOff() {
        var config = MatchConfig.defaults().withGroupMatching(false, 6, 7);
        assertThat(run(config, List.of(tx("S1", 0, 2000, "sale"), tx("S2", 0, 3000, "sale")), List.of(tx("P1", 1, 5000, "PAYOUT"))).matches()).isEmpty();
    }

    @Test
    void transactionIsNeverUsedTwice() {
        var config = MatchConfig.defaults().withDateWindow(10);
        var left = List.of(tx("L1", 0, 1000, "a"), tx("L2", 0, 1000, "a"), tx("L3", 0, 2000, "b"));
        var right = List.of(tx("R1", 0, 1000, "a"), tx("R2", 0, 2000, "b"), tx("R3", 0, 3000, "c"));
        var result = run(config, left, right);
        List<String> usedLeft = new ArrayList<>();
        List<String> usedRight = new ArrayList<>();
        result.matches().forEach(match -> {
            usedLeft.addAll(match.leftIds());
            usedRight.addAll(match.rightIds());
        });
        assertThat(usedLeft).doesNotHaveDuplicates();
        assertThat(usedRight).doesNotHaveDuplicates();
        assertThat(usedLeft.size() + result.unmatchedLeft().size()).isEqualTo(left.size());
        assertThat(usedRight.size() + result.unmatchedRight().size()).isEqualTo(right.size());
    }

    @Test
    void reportsPossibleDuplicates() {
        var result = run(MatchConfig.defaults(), List.of(), List.of(tx("R1", 0, 1500, "LUMEN TELECOM"), tx("R2", 0, 1500, "lumen telecom"), tx("R3", 0, 1500, "other")));
        assertThat(result.possibleDuplicatesRight()).containsExactly(List.of("R1", "R2"));
    }

    @Test
    void summaryAddsUp() {
        var result = run(MatchConfig.defaults(), List.of(tx("L1", 0, 100, "a"), tx("L2", 0, 250, "b")), List.of(tx("R1", 0, 100, "a"), tx("R2", 0, -75, "c")));
        var summary = result.summary();
        assertThat(summary.matchedLeft()).isEqualTo(1);
        assertThat(summary.unmatchedLeftTotalMinor()).isEqualTo(250);
        assertThat(summary.unmatchedRightTotalMinor()).isEqualTo(-75);
        assertThat(summary.matchesByType()).containsEntry(MatchType.EXACT, 1);
    }

    @Test
    void sameInputAlwaysGivesSameOutput() {
        var sample = io.github.narinehzargarian.recon.sample.SampleData.generate(3, 400);
        var config = MatchConfig.defaults().withAmountTolerance(50).withDateWindow(4);
        assertThat(run(config, sample.ledger(), sample.bank())).isEqualTo(run(config, sample.ledger(), sample.bank()));
    }

    @Test
    void emptyInputsAreFine() {
        var result = run(MatchConfig.defaults(), List.of(), List.of());
        assertThat(result.matches()).isEmpty();
        assertThat(result.summary().leftCount()).isZero();
    }

    @Test
    void duplicateIdsAreRejected() {
        assertThatThrownBy(() -> run(MatchConfig.defaults(), List.of(tx("X", 0, 1, "a"), tx("X", 1, 2, "b")), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate id 'X'");
    }

    @Test
    void invalidConfigIsRejected() {
        assertThatThrownBy(() -> MatchConfig.defaults().withAmountTolerance(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MatchConfig.defaults().withGroupMatching(true, 1, 7)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MatchConfig.defaults().withMinDescriptionSimilarity(1.5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void feesRoundHalfEven() {
        var config = MatchConfig.defaults().withFees(new BigDecimal("2.5"), 0);
        assertThat(config.feeFor(100)).isEqualTo(2);    // 2.5 rounds to the even 2.
        assertThat(config.feeFor(300)).isEqualTo(8);    // 7.5 rounds to the even 8.
        assertThat(config.feeFor(-300)).isEqualTo(8);   // Fees use the absolute amount.
    }
}
