package io.github.narinehzargarian.recon.io;

import io.github.narinehzargarian.recon.Transaction;
import java.util.List;

public record ReadResult(List<Transaction> transactions, List<RowError> errors) {

    public ReadResult {
        transactions = List.copyOf(transactions);
        errors = List.copyOf(errors);
    }
}
