package io.github.narinehzargarian.recon.io;

import io.github.narinehzargarian.recon.Match;
import io.github.narinehzargarian.recon.ReconciliationResult;
import io.github.narinehzargarian.recon.Transaction;
import java.io.IOException;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

/**
 * Writes a result as one CSV that opens cleanly in a spreadsheet: every match, then everything
 * unmatched. Amounts are in currency units, like 12.50, rather than the minor units used in code.
 */
public final class ReportWriter {

    private static final String[] HEADER = {
        "status", "match_type", "left_ids", "right_ids", "amount_difference", "fees", "max_date_difference_days", "ambiguous",
        "date", "amount", "currency", "description",
    };

    private ReportWriter() {
    }

    public static void writeCsv(ReconciliationResult result, Appendable out) throws IOException {
        try (CSVPrinter printer = CSVFormat.DEFAULT.builder().setHeader(HEADER).build().print(out)) {
            for (Match match : result.matches()) {
                printer.printRecord("matched", match.type(), String.join(" ", match.leftIds()), String.join(" ", match.rightIds()),
                        Amounts.format(match.amountDifferenceMinor(), match.currency()), Amounts.format(match.feesMinor(), match.currency()),
                        match.maxDateDifferenceDays(), match.ambiguous(), "", "", match.currency(), "");
            }
            printUnmatched(printer, "unmatched_left", result.unmatchedLeft(), true);
            printUnmatched(printer, "unmatched_right", result.unmatchedRight(), false);
        }
    }

    private static void printUnmatched(CSVPrinter printer, String status, List<Transaction> transactions, boolean left) throws IOException {
        for (Transaction t : transactions) {
            printer.printRecord(status, "", left ? t.id() : "", left ? "" : t.id(), "", "", "", "",
                    t.date(), Amounts.format(t.amountMinor(), t.currency()), t.currency(), safe(t.description()));
        }
    }

    /** Stops spreadsheet formula injection: text starting with these characters could run as a formula. */
    static String safe(String text) {
        return !text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0 ? "'" + text : text;
    }
}
