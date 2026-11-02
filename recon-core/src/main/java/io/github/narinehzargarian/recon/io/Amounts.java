package io.github.narinehzargarian.recon.io;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/** Parses amounts as banks and spreadsheets actually write them, into exact minor units. */
public final class Amounts {

    private Amounts() {
    }

    /**
     * Accepts forms like {@code 1234.56}, {@code -1,234.56}, {@code $1,234.56}, {@code (12.00)},
     * {@code 12.00-}, {@code +5}, and with {@code decimalComma}, {@code 1.234,56}.
     *
     * @throws IllegalArgumentException if the text isn't an amount, or has more decimals than the currency allows
     */
    public static long parseMinor(String text, String currency, boolean decimalComma) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("amount is empty");
        }
        String value = text.trim();
        boolean negative = false;
        if (value.startsWith("(") && value.endsWith(")")) {
            negative = true;
            value = value.substring(1, value.length() - 1).trim();
        }
        if (value.endsWith("-")) {
            negative = !negative;
            value = value.substring(0, value.length() - 1).trim();
        }
        if (value.startsWith("-")) {
            negative = !negative;
            value = value.substring(1).trim();
        } else if (value.startsWith("+")) {
            value = value.substring(1).trim();
        }
        // Currency symbols and codes, such as "$", "EUR" or "USD ".
        value = value.replaceAll("^[^0-9.,]+", "").replaceAll("[^0-9.,]+$", "").trim();
        if (decimalComma) {
            value = value.replace(".", "").replace(" ", "").replace(",", ".");
        } else {
            value = value.replace(",", "").replace(" ", "");
        }
        if (!value.matches("\\d+(\\.\\d+)?|\\.\\d+")) {
            throw new IllegalArgumentException("'" + text + "' is not an amount");
        }
        int digits = fractionDigits(currency);
        BigDecimal amount = new BigDecimal(value);
        if (amount.scale() > digits && amount.stripTrailingZeros().scale() > digits) {
            throw new IllegalArgumentException("'" + text + "' has more decimal places than " + currency + " allows");
        }
        long minor = amount.setScale(digits, RoundingMode.UNNECESSARY).movePointRight(digits).longValueExact();
        return negative ? -minor : minor;
    }

    /** Decimal places for a currency: 2 for USD, 0 for JPY. Unknown codes default to 2. */
    public static int fractionDigits(String currency) {
        try {
            int digits = Currency.getInstance(currency).getDefaultFractionDigits();
            return digits < 0 ? 2 : digits;
        } catch (IllegalArgumentException unknown) {
            return 2;
        }
    }

    /** Formats minor units back to text, like {@code -1234.56}. */
    public static String format(long minor, String currency) {
        return BigDecimal.valueOf(minor, fractionDigits(currency)).toPlainString();
    }
}
