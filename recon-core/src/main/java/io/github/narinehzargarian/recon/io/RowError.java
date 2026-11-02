package io.github.narinehzargarian.recon.io;

/** A row that couldn't be read. Reading continues, so one bad line doesn't hide the rest. */
public record RowError(long line, String message) {
}
