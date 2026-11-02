package io.github.narinehzargarian.recon;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;

/**
 * One line from either source. Amounts are whole minor units (cents for USD), never floating
 * point, so sums are exact.
 */
public record Transaction(String id, LocalDate date, long amountMinor, String currency, String description, String reference) {

    public Transaction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(currency, "currency");
        currency = currency.trim().toUpperCase(Locale.ROOT);
        description = description == null ? "" : description.trim();
        reference = reference == null || reference.isBlank() ? null : reference.trim();
    }

    public static Transaction of(String id, LocalDate date, long amountMinor, String description) {
        return new Transaction(id, date, amountMinor, "USD", description, null);
    }
}
