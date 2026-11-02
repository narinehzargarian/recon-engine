package io.github.narinehzargarian.recon.io;

import io.github.narinehzargarian.recon.Transaction;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads bank statement downloads in OFX or QFX format, versions 1 and 2.
 *
 * <p>Version 1 is SGML, where most tags are never closed, so this reads each field up to the next
 * tag rather than using an XML parser. That handles both versions the same way.
 */
public final class OfxTransactionReader {

    private static final Pattern TRANSACTION = Pattern.compile("<STMTTRN>(.*?)(?:</STMTTRN>|(?=<STMTTRN>)|(?=</BANKTRANLIST>))",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CURRENCY = Pattern.compile("<CURDEF>\\s*([A-Za-z]{3})", Pattern.CASE_INSENSITIVE);

    /**
     * Uses FITID, the bank's own ID for each transaction, as the ID. The description is NAME,
     * plus MEMO when it adds something, and CHECKNUM, when present, becomes the reference.
     */
    public ReadResult read(String content, int maxRows) {
        String currency = "USD";
        Matcher currencyMatch = CURRENCY.matcher(content);
        if (currencyMatch.find()) {
            currency = currencyMatch.group(1).toUpperCase(Locale.ROOT);
        }
        if (!content.toUpperCase(Locale.ROOT).contains("<OFX>")) {
            throw new IllegalArgumentException("not an OFX file: no <OFX> tag found");
        }

        List<Transaction> transactions = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Matcher matcher = TRANSACTION.matcher(content);
        int number = 0;
        while (matcher.find()) {
            number++;
            if (number > maxRows) {
                errors.add(new RowError(number, "stopped after " + maxRows + " transactions"));
                break;
            }
            String block = matcher.group(1);
            try {
                String rawDate = field(block, "DTPOSTED");
                if (rawDate.length() < 8) {
                    throw new IllegalArgumentException("DTPOSTED '" + rawDate + "' is not a date");
                }
                // Dates look like 20261101 or 20261101120000.000[-5:EST]; only the day matters here.
                LocalDate date = LocalDate.of(Integer.parseInt(rawDate.substring(0, 4)),
                        Integer.parseInt(rawDate.substring(4, 6)), Integer.parseInt(rawDate.substring(6, 8)));
                long amount = Amounts.parseMinor(field(block, "TRNAMT"), currency, false);
                String id = field(block, "FITID");
                id = id.isEmpty() ? "ofx-" + number : id;
                if (!ids.add(id)) {
                    throw new IllegalArgumentException("FITID '" + id + "' appears more than once");
                }
                String name = field(block, "NAME");
                String memo = field(block, "MEMO");
                String description = memo.isEmpty() || name.contains(memo) ? name : (name + " " + memo).trim();
                String check = field(block, "CHECKNUM");
                transactions.add(new Transaction(id, date, amount, currency, decode(description), check.isEmpty() ? null : check));
            } catch (IllegalArgumentException | ArithmeticException | java.time.DateTimeException error) {
                errors.add(new RowError(number, "transaction " + number + ": " + error.getMessage()));
            }
        }
        return new ReadResult(transactions, errors);
    }

    private static String field(String block, String tag) {
        Matcher matcher = Pattern.compile("<" + tag + ">([^<\\r\\n]*)", Pattern.CASE_INSENSITIVE).matcher(block);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String decode(String text) {
        return text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&apos;", "'").replace("&quot;", "\"");
    }
}
