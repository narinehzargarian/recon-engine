package io.github.narinehzargarian.recon;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.narinehzargarian.recon.sample.SampleData;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Measures matching quality against the known answer in generated data. These numbers describe
 * the generator's patterns, so they show the engine works, not how it will do on your bank's data.
 */
class AccuracyAndScaleTest {

    static MatchConfig sampleConfig() {
        return MatchConfig.defaults()
                .withAmountTolerance(50)
                .withDateWindow(4)
                .withFees(SampleData.FEE_PERCENT, SampleData.FEE_FIXED_MINOR)
                .withGroupMatching(true, 6, 5);
    }

    static double[] precisionRecall(SampleData.Sample sample, ReconciliationResult result) {
        Set<String> found = new HashSet<>();
        for (Match match : result.matches()) {
            for (String l : match.leftIds()) {
                for (String r : match.rightIds()) {
                    found.add(l + "|" + r);
                }
            }
        }
        long correct = found.stream().filter(sample.truthLinks()::contains).count();
        return new double[] {(double) correct / found.size(), (double) correct / sample.truthLinks().size()};
    }

    @Test
    void matchesGeneratedDataAccurately() {
        double worstPrecision = 1;
        double worstRecall = 1;
        for (long seed = 1; seed <= 5; seed++) {
            var sample = SampleData.generate(seed, 2000);
            var result = new Reconciler(sampleConfig()).reconcile(sample.ledger(), sample.bank());
            double[] score = precisionRecall(sample, result);
            worstPrecision = Math.min(worstPrecision, score[0]);
            worstRecall = Math.min(worstRecall, score[1]);
            System.out.printf("seed %d: precision %.4f, recall %.4f, by type %s, ambiguous %d%n",
                    seed, score[0], score[1], result.summary().matchesByType(), result.summary().ambiguousMatches());
        }
        assertThat(worstPrecision).isGreaterThan(0.97);
        assertThat(worstRecall).isGreaterThan(0.95);
    }

    @Test
    void handlesFiftyThousandTransactionsPerSide() {
        var sample = SampleData.generate(42, 60_000);
        long start = System.nanoTime();
        var result = new Reconciler(sampleConfig()).reconcile(sample.ledger(), sample.bank());
        double seconds = (System.nanoTime() - start) / 1e9;
        double[] score = precisionRecall(sample, result);
        System.out.printf("scale: %d ledger, %d bank lines in %.2f s, precision %.4f, recall %.4f%n",
                sample.ledger().size(), sample.bank().size(), seconds, score[0], score[1]);
        assertThat(seconds).isLessThan(60);
        assertThat(score[0]).isGreaterThan(0.9);
    }
}
