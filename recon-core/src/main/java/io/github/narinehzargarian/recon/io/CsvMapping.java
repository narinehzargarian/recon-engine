package io.github.narinehzargarian.recon.io;

/**
 * Which CSV columns hold what. Columns are matched by header name, ignoring case and spaces.
 *
 * <p>Give either {@code amount}, or {@code debit} and {@code credit} for files that split them into
 * two columns. Everything except {@code date} and the amount columns is optional.
 *
 * @param dateFormat a {@link java.time.format.DateTimeFormatter} pattern, such as {@code MM/dd/yyyy}
 * @param negate flips every sign, for sources that record money out as positive
 * @param defaultCurrency used when there's no currency column, or it's empty
 * @param decimalComma for amounts written like {@code 1.234,56}
 */
public record CsvMapping(
        String id,
        String date,
        String dateFormat,
        String amount,
        String debit,
        String credit,
        String description,
        String reference,
        String currency,
        String defaultCurrency,
        boolean negate,
        boolean decimalComma,
        char delimiter) {

    public CsvMapping {
        if (date == null || date.isBlank()) {
            throw new IllegalArgumentException("mapping needs a date column");
        }
        boolean hasAmount = amount != null && !amount.isBlank();
        boolean hasSplit = debit != null && !debit.isBlank() && credit != null && !credit.isBlank();
        if (hasAmount == hasSplit) {
            throw new IllegalArgumentException("mapping needs either an amount column, or both debit and credit columns");
        }
        dateFormat = dateFormat == null || dateFormat.isBlank() ? "yyyy-MM-dd" : dateFormat;
        defaultCurrency = defaultCurrency == null || defaultCurrency.isBlank() ? "USD" : defaultCurrency;
        delimiter = delimiter == 0 ? ',' : delimiter;
    }

    /** Columns named id, date (yyyy-MM-dd), amount, currency, description, and reference. */
    public static CsvMapping standard() {
        return new CsvMapping("id", "date", "yyyy-MM-dd", "amount", null, null, "description", "reference", "currency", "USD", false, false, ',');
    }
}
