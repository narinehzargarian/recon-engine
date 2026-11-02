package io.github.narinehzargarian.recon.cli;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.narinehzargarian.recon.MatchConfig;
import io.github.narinehzargarian.recon.Reconciler;
import io.github.narinehzargarian.recon.ReconciliationResult;
import io.github.narinehzargarian.recon.Transaction;
import io.github.narinehzargarian.recon.io.Amounts;
import io.github.narinehzargarian.recon.io.CsvMapping;
import io.github.narinehzargarian.recon.io.CsvTransactionReader;
import io.github.narinehzargarian.recon.io.OfxTransactionReader;
import io.github.narinehzargarian.recon.io.ReadResult;
import io.github.narinehzargarian.recon.io.ReportWriter;
import io.github.narinehzargarian.recon.io.RowError;
import io.github.narinehzargarian.recon.sample.SampleData;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "recon", mixinStandardHelpOptions = true, version = "recon 0.1.0",
        description = "Matches transactions between two files, such as a bank statement and a ledger.",
        subcommands = {ReconCli.MatchCommand.class, ReconCli.SampleCommand.class})
public final class ReconCli {

    /** Everything matched, or there was nothing to compare. */
    static final int OK = 0;
    /** Matching finished, but something was left unmatched, and --fail-on-unmatched was given. */
    static final int UNMATCHED = 1;
    /** The input couldn't be used: a missing file, a bad mapping, or rows that failed with --strict. */
    static final int INPUT_ERROR = 2;

    static final int MAX_ROWS = 1_000_000;

    private ReconCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, new PrintWriter(System.out, true, StandardCharsets.UTF_8), new PrintWriter(System.err, true, StandardCharsets.UTF_8)));
    }

    static int run(String[] args, PrintWriter out, PrintWriter err) {
        CommandLine commandLine = new CommandLine(new ReconCli());
        commandLine.setOut(out);
        commandLine.setErr(err);
        commandLine.setExecutionExceptionHandler((error, line, parse) -> {
            err.println("error: " + error.getMessage());
            return INPUT_ERROR;
        });
        int code = commandLine.execute(args);
        // Picocli reports bad arguments as 2 as well.
        return code == CommandLine.ExitCode.USAGE ? INPUT_ERROR : code;
    }

    static ObjectMapper json() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Command(name = "match", mixinStandardHelpOptions = true,
            description = "Reconciles LEFT against RIGHT. Files ending in .ofx or .qfx are read as OFX, everything else as CSV.")
    static final class MatchCommand implements Callable<Integer> {

        @Parameters(index = "0", paramLabel = "LEFT", description = "Usually your ledger or accounting export.")
        Path left;

        @Parameters(index = "1", paramLabel = "RIGHT", description = "Usually the bank statement.")
        Path right;

        @Option(names = "--left-mapping", paramLabel = "FILE", description = "JSON column mapping for LEFT. Default: columns named id, date, amount, currency, description, reference.")
        Path leftMapping;

        @Option(names = "--right-mapping", paramLabel = "FILE", description = "JSON column mapping for RIGHT.")
        Path rightMapping;

        @Option(names = "--tolerance", paramLabel = "AMOUNT", defaultValue = "0", description = "Largest allowed amount difference, like 0.50. Default: ${DEFAULT-VALUE}.")
        String tolerance;

        @Option(names = "--date-window", paramLabel = "DAYS", defaultValue = "3", description = "Largest allowed date difference. Default: ${DEFAULT-VALUE}.")
        int dateWindow;

        @Option(names = "--min-similarity", paramLabel = "0-1", defaultValue = "0", description = "Minimum description similarity. Default: off.")
        double minSimilarity;

        @Option(names = "--no-groups", description = "Turn off matching one transaction against several.")
        boolean noGroups;

        @Option(names = "--max-group-size", defaultValue = "6", description = "Default: ${DEFAULT-VALUE}.")
        int maxGroupSize;

        @Option(names = "--group-window", paramLabel = "DAYS", defaultValue = "7", description = "How far back a group's members may be. Default: ${DEFAULT-VALUE}.")
        int groupWindow;

        @Option(names = "--group-tolerance", paramLabel = "AMOUNT", defaultValue = "0",
                description = "Largest allowed difference for group matches, after fees. Default: exact.")
        String groupTolerance;

        @Option(names = "--fee-percent", paramLabel = "PERCENT", defaultValue = "0", description = "Fee per grouped transaction, like 2.9 for card payouts.")
        BigDecimal feePercent;

        @Option(names = "--fee-fixed", paramLabel = "AMOUNT", defaultValue = "0", description = "Fixed fee per grouped transaction, like 0.30.")
        String feeFixed;

        @Option(names = "--json", paramLabel = "FILE", description = "Write the full result as JSON.")
        Path jsonOut;

        @Option(names = "--csv", paramLabel = "FILE", description = "Write a spreadsheet-friendly report.")
        Path csvOut;

        @Option(names = "--fail-on-unmatched", description = "Exit with code 1 if anything is unmatched. Useful in scheduled jobs.")
        boolean failOnUnmatched;

        @Option(names = "--strict", description = "Exit with code 2 if any row can't be read, instead of skipping it.")
        boolean strict;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() throws IOException {
            PrintWriter out = spec.commandLine().getOut();
            PrintWriter err = spec.commandLine().getErr();
            ObjectMapper mapper = json();
            ReadResult leftRead = read(left, leftMapping, mapper);
            ReadResult rightRead = read(right, rightMapping, mapper);
            report(err, "left", left, leftRead.errors());
            report(err, "right", right, rightRead.errors());
            if (strict && (!leftRead.errors().isEmpty() || !rightRead.errors().isEmpty())) {
                return INPUT_ERROR;
            }

            String currency = firstCurrency(leftRead.transactions(), rightRead.transactions());
            MatchConfig config = new MatchConfig(
                    Math.abs(Amounts.parseMinor(tolerance, currency, false)), dateWindow, minSimilarity, !noGroups, maxGroupSize,
                    groupWindow, Math.abs(Amounts.parseMinor(groupTolerance, currency, false)), feePercent,
                    Math.abs(Amounts.parseMinor(feeFixed, currency, false)), 300);
            ReconciliationResult result = new Reconciler(config).reconcile(leftRead.transactions(), rightRead.transactions());

            if (jsonOut != null) {
                mapper.writeValue(jsonOut.toFile(), result);
            }
            if (csvOut != null) {
                try (Writer writer = Files.newBufferedWriter(csvOut, StandardCharsets.UTF_8)) {
                    ReportWriter.writeCsv(result, writer);
                }
            }
            printSummary(out, result, currency);
            boolean anythingUnmatched = !result.unmatchedLeft().isEmpty() || !result.unmatchedRight().isEmpty();
            return failOnUnmatched && anythingUnmatched ? UNMATCHED : OK;
        }

        private static ReadResult read(Path file, Path mappingFile, ObjectMapper mapper) throws IOException {
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("file not found: " + file);
            }
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".ofx") || name.endsWith(".qfx")) {
                return new OfxTransactionReader().read(Files.readString(file, StandardCharsets.ISO_8859_1), MAX_ROWS);
            }
            CsvMapping mapping = mappingFile == null ? CsvMapping.standard() : mapper.readValue(mappingFile.toFile(), CsvMapping.class);
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                return new CsvTransactionReader(mapping).read(reader, MAX_ROWS);
            }
        }

        private static void report(PrintWriter err, String side, Path file, List<RowError> errors) {
            for (RowError error : errors.subList(0, Math.min(errors.size(), 20))) {
                err.printf("warning: %s line %d: %s%n", file.getFileName(), error.line(), error.message());
            }
            if (errors.size() > 20) {
                err.printf("warning: %d more unreadable rows in %s (%s)%n", errors.size() - 20, file.getFileName(), side);
            }
        }

        private static String firstCurrency(List<Transaction> left, List<Transaction> right) {
            if (!left.isEmpty()) {
                return left.get(0).currency();
            }
            return right.isEmpty() ? "USD" : right.get(0).currency();
        }

        private static void printSummary(PrintWriter out, ReconciliationResult result, String currency) {
            var summary = result.summary();
            out.printf("Left:  %d transactions, %d matched, %d unmatched (total %s)%n", summary.leftCount(), summary.matchedLeft(),
                    result.unmatchedLeft().size(), Amounts.format(summary.unmatchedLeftTotalMinor(), currency));
            out.printf("Right: %d transactions, %d matched, %d unmatched (total %s)%n", summary.rightCount(), summary.matchedRight(),
                    result.unmatchedRight().size(), Amounts.format(summary.unmatchedRightTotalMinor(), currency));
            out.printf("Matches: %s%n", summary.matchesByType());
            if (summary.ambiguousMatches() > 0) {
                out.printf("Review: %d ambiguous matches had an equally good alternative%n", summary.ambiguousMatches());
            }
            int duplicates = result.possibleDuplicatesLeft().size() + result.possibleDuplicatesRight().size();
            if (duplicates > 0) {
                out.printf("Review: %d groups of possible duplicates%n", duplicates);
            }
            if (summary.groupSearchLimitHits() > 0) {
                out.printf("Note: %d group searches stopped early; try a smaller --max-group-size%n", summary.groupSearchLimitHits());
            }
        }
    }

    @Command(name = "sample", mixinStandardHelpOptions = true,
            description = "Writes a generated ledger.csv and bank.csv to try the tool with.")
    static final class SampleCommand implements Callable<Integer> {

        @Option(names = "--out", paramLabel = "DIR", defaultValue = ".", description = "Default: the current directory.")
        Path out;

        @Option(names = "--size", defaultValue = "500", description = "Ledger rows. Default: ${DEFAULT-VALUE}.")
        int size;

        @Option(names = "--seed", defaultValue = "1")
        long seed;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() throws IOException {
            if (size < 1 || size > MAX_ROWS) {
                throw new IllegalArgumentException("--size must be between 1 and " + MAX_ROWS);
            }
            Files.createDirectories(out);
            var sample = SampleData.generate(seed, size);
            write(out.resolve("ledger.csv"), sample.ledger());
            write(out.resolve("bank.csv"), sample.bank());
            spec.commandLine().getOut().printf("Wrote %d ledger and %d bank rows to %s%nTry: recon match %s %s --tolerance 0.50 --date-window 4 --fee-percent 2.9 --fee-fixed 0.30%n",
                    sample.ledger().size(), sample.bank().size(), out, out.resolve("ledger.csv"), out.resolve("bank.csv"));
            return OK;
        }

        private static void write(Path file, List<Transaction> transactions) throws IOException {
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
                 var printer = org.apache.commons.csv.CSVFormat.DEFAULT.builder()
                         .setHeader("id", "date", "amount", "currency", "description", "reference").build().print(writer)) {
                for (Transaction t : transactions) {
                    printer.printRecord(t.id(), t.date(), Amounts.format(t.amountMinor(), t.currency()), t.currency(),
                            t.description(), t.reference() == null ? "" : t.reference());
                }
            }
        }
    }
}
