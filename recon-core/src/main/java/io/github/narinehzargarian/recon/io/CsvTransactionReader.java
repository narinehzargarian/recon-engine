package io.github.narinehzargarian.recon.io;

import io.github.narinehzargarian.recon.Transaction;
import java.io.IOException;
import java.io.Reader;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/** Reads transactions from any CSV with a header row, using a {@link CsvMapping}. */
public final class CsvTransactionReader {

    private final CsvMapping mapping;
    private final DateTimeFormatter dateFormat;

    public CsvTransactionReader(CsvMapping mapping) {
        this.mapping = mapping;
        this.dateFormat = DateTimeFormatter.ofPattern(mapping.dateFormat(), Locale.ROOT);
    }

    /**
     * @param maxRows stops with an error past this many rows, so a huge upload can't exhaust memory
     * @throws IOException if the CSV itself can't be read
     * @throws IllegalArgumentException if a mapped column is missing from the header
     */
    public ReadResult read(Reader input, int maxRows) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(mapping.delimiter())
                .setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true).setTrim(true)
                .build();
        List<Transaction> transactions = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try (CSVParser parser = format.parse(input)) {
            Map<String, String> columns = headerLookup(parser.getHeaderNames());
            String idColumn = optionalColumn(columns, mapping.id());
            String dateColumn = requiredColumn(columns, mapping.date());
            String amountColumn = optionalColumn(columns, mapping.amount());
            String debitColumn = optionalColumn(columns, mapping.debit());
            String creditColumn = optionalColumn(columns, mapping.credit());
            if (mapping.amount() != null && amountColumn == null) {
                throw new IllegalArgumentException("column '" + mapping.amount() + "' not found; the header has " + parser.getHeaderNames());
            }
            if (mapping.amount() == null && (debitColumn == null || creditColumn == null)) {
                throw new IllegalArgumentException("debit or credit column not found; the header has " + parser.getHeaderNames());
            }
            String descriptionColumn = optionalColumn(columns, mapping.description());
            String referenceColumn = optionalColumn(columns, mapping.reference());
            String currencyColumn = optionalColumn(columns, mapping.currency());

            for (CSVRecord record : parser) {
                if (transactions.size() + errors.size() >= maxRows) {
                    errors.add(new RowError(parser.getCurrentLineNumber(), "stopped after " + maxRows + " rows"));
                    break;
                }
                long line = parser.getCurrentLineNumber();
                try {
                    String currency = value(record, currencyColumn);
                    currency = currency.isEmpty() ? mapping.defaultCurrency() : currency;
                    long amount;
                    if (amountColumn != null) {
                        amount = Amounts.parseMinor(value(record, amountColumn), currency, mapping.decimalComma());
                    } else {
                        String debit = value(record, debitColumn);
                        String credit = value(record, creditColumn);
                        if (!debit.isEmpty() && !credit.isEmpty()) {
                            throw new IllegalArgumentException("both debit and credit are filled in");
                        }
                        if (debit.isEmpty() && credit.isEmpty()) {
                            throw new IllegalArgumentException("neither debit nor credit is filled in");
                        }
                        // Debits are money out, so they're negative.
                        amount = debit.isEmpty()
                                ? Math.abs(Amounts.parseMinor(credit, currency, mapping.decimalComma()))
                                : -Math.abs(Amounts.parseMinor(debit, currency, mapping.decimalComma()));
                    }
                    if (mapping.negate()) {
                        amount = -amount;
                    }
                    LocalDate date = LocalDate.parse(value(record, dateColumn), dateFormat);
                    String id = value(record, idColumn);
                    id = id.isEmpty() ? "row-" + line : id;
                    if (!ids.add(id)) {
                        throw new IllegalArgumentException("id '" + id + "' appears more than once");
                    }
                    transactions.add(new Transaction(id, date, amount, currency,
                            value(record, descriptionColumn), value(record, referenceColumn)));
                } catch (DateTimeParseException error) {
                    errors.add(new RowError(line, "date '" + value(record, dateColumn) + "' doesn't match " + mapping.dateFormat()));
                } catch (IllegalArgumentException | ArithmeticException error) {
                    errors.add(new RowError(line, error.getMessage()));
                }
            }
        }
        return new ReadResult(transactions, errors);
    }

    private static Map<String, String> headerLookup(List<String> headers) {
        Map<String, String> lookup = new HashMap<>();
        for (String header : headers) {
            lookup.put(normalize(header), header);
        }
        return lookup;
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "").replace("﻿", "");
    }

    private static String requiredColumn(Map<String, String> columns, String name) {
        String found = columns.get(normalize(name));
        if (found == null) {
            throw new IllegalArgumentException("column '" + name + "' not found; the header has " + columns.values());
        }
        return found;
    }

    private static String optionalColumn(Map<String, String> columns, String name) {
        return name == null || name.isBlank() ? null : columns.get(normalize(name));
    }

    private static String value(CSVRecord record, String column) {
        if (column == null || !record.isSet(column)) {
            return "";
        }
        String value = record.get(column);
        return value == null ? "" : value.trim();
    }
}
