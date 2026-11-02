package io.github.narinehzargarian.recon.sample;

import io.github.narinehzargarian.recon.MatchConfig;
import io.github.narinehzargarian.recon.Transaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Generates a ledger and a matching bank statement with realistic messiness, plus the true answer.
 * Useful for trying the tool, and for measuring how well matching works. It's synthetic, so it
 * can't stand in for testing against your own real data.
 *
 * <p>The bank side has: posting delays of 0 to 3 days, small amount differences on some lines,
 * reworded descriptions, card payouts that combine several sales less fees, ledger entries that
 * never cleared, bank-only fees and interest, and the occasional duplicated line.
 */
public final class SampleData {

    /** Stripe-style card fees: 2.9% plus 30 cents per charge. */
    public static final BigDecimal FEE_PERCENT = new BigDecimal("2.9");
    public static final long FEE_FIXED_MINOR = 30;

    private static final String[] VENDORS = {
        "Acme Office Supply", "Blue Harbor Logistics", "Cedar Software", "Delta Utilities", "Evergreen Rent",
        "Foxglove Catering", "Granite Insurance", "Helios Energy", "Ironwood Legal", "Juniper Cloud",
        "Kestrel Marketing", "Lumen Telecom", "Maple Payroll", "Northstar Freight", "Orchid Design",
    };

    /**
     * @param truthLinks every true link, as "leftId|rightId"
     */
    public record Sample(List<Transaction> ledger, List<Transaction> bank, Set<String> truthLinks) {
    }

    private SampleData() {
    }

    public static Sample generate(long seed, int ledgerCount) {
        Random random = new Random(seed);
        MatchConfig fees = MatchConfig.defaults().withFees(FEE_PERCENT, FEE_FIXED_MINOR);
        LocalDate start = LocalDate.of(2026, 1, 1);
        List<Transaction> ledger = new ArrayList<>();
        List<Transaction> bank = new ArrayList<>();
        Set<String> truth = new HashSet<>();
        int bankNumber = 0;

        // Dates in order, as a ledger is written, so payouts can group sales from the same few days.
        // About 15 ledger entries a day, like a small business, over at least six months.
        int days = Math.max(180, ledgerCount / 15);
        int[] dayOffsets = new int[ledgerCount];
        for (int n = 0; n < ledgerCount; n++) {
            dayOffsets[n] = random.nextInt(days);
        }
        java.util.Arrays.sort(dayOffsets);

        List<Transaction> pendingSales = new ArrayList<>();
        int payoutSize = 2 + random.nextInt(4);
        for (int n = 0; n < ledgerCount; n++) {
            LocalDate date = start.plusDays(dayOffsets[n]);
            // A payout covers sales from at most the last 3 days, as card processors pay out daily.
            if (!pendingSales.isEmpty() && pendingSales.get(0).date().plusDays(3).isBefore(date)) {
                bankNumber = payOut(pendingSales, fees, random, bank, truth, bankNumber);
                payoutSize = 2 + random.nextInt(4);
            }
            String ledgerId = "L" + (100000 + n);
            double kind = random.nextDouble();
            if (kind < 0.15) {
                // Card sales, paid out later in one combined deposit.
                long amount = 500 + random.nextInt(40000);
                Transaction sale = new Transaction(ledgerId, date, amount, "USD", "Card sale #" + (5000 + n), null);
                ledger.add(sale);
                pendingSales.add(sale);
                if (pendingSales.size() >= payoutSize) {
                    bankNumber = payOut(pendingSales, fees, random, bank, truth, bankNumber);
                    payoutSize = 2 + random.nextInt(4);
                }
                continue;
            }

            String vendor = VENDORS[random.nextInt(VENDORS.length)];
            boolean income = random.nextDouble() < 0.3;
            long amount = (long) Math.max(100, Math.exp(7 + random.nextGaussian() * 1.4));
            amount = income ? amount : -amount;
            String invoice = "INV-" + (20000 + n);
            boolean hasReference = random.nextDouble() < 0.5;
            ledger.add(new Transaction(ledgerId, date, amount, "USD",
                    (income ? "Payment from " : "Payment to ") + vendor + " " + invoice, hasReference ? invoice : null));

            if (kind < 0.20) {
                continue;  // Never cleared the bank, such as an uncashed check.
            }
            long bankAmount = amount;
            if (random.nextDouble() < 0.04) {
                bankAmount += (random.nextBoolean() ? 1 : -1) * (1 + random.nextInt(40));  // Small difference, such as a wire fee.
            }
            LocalDate bankDate = date.plusDays(random.nextInt(4));
            String bankText = vendor.toUpperCase().substring(0, Math.min(vendor.length(), 12 + random.nextInt(6)))
                    + " " + (100000 + random.nextInt(900000));
            String bankId = "B" + (bankNumber++);
            boolean bankReference = hasReference && random.nextDouble() < 0.6;
            bank.add(new Transaction(bankId, bankDate, bankAmount, "USD", bankText, bankReference ? invoice : null));
            truth.add(ledgerId + "|" + bankId);
            if (random.nextDouble() < 0.01) {
                // A duplicated bank line. It matches nothing, and should show up as a possible duplicate.
                bank.add(new Transaction("B" + (bankNumber++), bankDate, bankAmount, "USD", bankText, null));
            }
        }
        if (pendingSales.size() >= 2) {
            bankNumber = payOut(pendingSales, fees, random, bank, truth, bankNumber);
        } else {
            pendingSales.clear();  // A single leftover sale would be paid out in the next period.
        }
        int extras = Math.max(1, ledgerCount / 30);
        for (int k = 0; k < extras; k++) {
            // Bank-only lines: fees and interest the ledger hasn't recorded yet.
            boolean fee = random.nextBoolean();
            bank.add(new Transaction("B" + (bankNumber++), start.plusDays(random.nextInt(days)),
                    fee ? -(500 + random.nextInt(3000)) : 1 + random.nextInt(900), "USD", fee ? "MONTHLY SERVICE FEE" : "INTEREST PAID", null));
        }
        return new Sample(ledger, bank, truth);
    }

    private static int payOut(List<Transaction> sales, MatchConfig fees, Random random, List<Transaction> bank, Set<String> truth, int bankNumber) {
        if (sales.size() == 1) {
            // A single sale is paid out alone, so it's a one-to-one match less fees.
            sales.add(0, sales.get(0));
            sales.remove(0);
        }
        LocalDate payoutDate = sales.stream().map(Transaction::date).max(LocalDate::compareTo).orElseThrow().plusDays(2);
        long net = 0;
        for (Transaction s : sales) {
            net += s.amountMinor() - fees.feeFor(s.amountMinor());
        }
        String bankId = "B" + bankNumber;
        bank.add(new Transaction(bankId, payoutDate, net, "USD", "STRIPE PAYOUT " + random.nextInt(99999), null));
        for (Transaction s : sales) {
            truth.add(s.id() + "|" + bankId);
        }
        sales.clear();
        return bankNumber + 1;
    }
}
