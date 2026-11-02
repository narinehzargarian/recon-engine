package io.github.narinehzargarian.recon.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReconCliTest {

    @TempDir
    Path dir;

    private record Run(int code, String out, String err) {
    }

    private static Run run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = ReconCli.run(args, new PrintWriter(out, true), new PrintWriter(err, true));
        return new Run(code, out.toString(), err.toString());
    }

    @Test
    void sampleThenMatchProducesReports() throws Exception {
        assertThat(run("sample", "--out", dir.toString(), "--size", "300").code()).isZero();
        Path json = dir.resolve("result.json");
        Path csv = dir.resolve("report.csv");
        Run result = run("match", dir.resolve("ledger.csv").toString(), dir.resolve("bank.csv").toString(),
                "--tolerance", "0.50", "--date-window", "4", "--fee-percent", "2.9", "--fee-fixed", "0.30",
                "--json", json.toString(), "--csv", csv.toString());
        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("Left:", "Right:", "GROUP");
        assertThat(Files.readString(json)).contains("\"matches\"", "\"summary\"");
        assertThat(Files.readAllLines(csv).get(0)).startsWith("status,match_type");
    }

    @Test
    void failOnUnmatchedReturnsOneWhenSomethingIsLeftOver() throws Exception {
        Path left = Files.writeString(dir.resolve("l.csv"), "id,date,amount\nL1,2026-01-01,10.00\n");
        Path right = Files.writeString(dir.resolve("r.csv"), "id,date,amount\nR1,2026-01-01,10.00\nR2,2026-01-02,3.00\n");
        assertThat(run("match", left.toString(), right.toString()).code()).isZero();
        assertThat(run("match", left.toString(), right.toString(), "--fail-on-unmatched").code()).isEqualTo(1);
    }

    @Test
    void customMappingFileIsUsed() throws Exception {
        Path left = Files.writeString(dir.resolve("l.csv"), "Posted,Details,Debit,Credit\n01/05/2026,Rent,1500.00,\n");
        Path right = Files.writeString(dir.resolve("r.csv"), "date,amount\n2026-01-05,-1500.00\n");
        Path mapping = Files.writeString(dir.resolve("m.json"),
                "{\"date\":\"Posted\",\"dateFormat\":\"MM/dd/yyyy\",\"debit\":\"Debit\",\"credit\":\"Credit\",\"description\":\"Details\"}");
        Run result = run("match", left.toString(), right.toString(), "--left-mapping", mapping.toString(), "--fail-on-unmatched");
        assertThat(result.code()).as(result.err()).isZero();
    }

    @Test
    void badRowsWarnByDefaultAndFailWithStrict() throws Exception {
        Path left = Files.writeString(dir.resolve("l.csv"), "date,amount\n2026-01-01,10.00\nbad,1\n");
        Path right = Files.writeString(dir.resolve("r.csv"), "date,amount\n2026-01-01,10.00\n");
        Run lenient = run("match", left.toString(), right.toString());
        assertThat(lenient.code()).isZero();
        assertThat(lenient.err()).contains("warning: l.csv line 3");
        assertThat(run("match", left.toString(), right.toString(), "--strict").code()).isEqualTo(2);
    }

    @Test
    void missingFileAndBadOptionsExitWithTwo() {
        Run missing = run("match", dir.resolve("nope.csv").toString(), dir.resolve("also-nope.csv").toString());
        assertThat(missing.code()).isEqualTo(2);
        assertThat(missing.err()).contains("file not found");
        assertThat(run("match", "a.csv").code()).isEqualTo(2);
        assertThat(run("match", "a.csv", "b.csv", "--date-window", "soon").code()).isEqualTo(2);
    }

    @Test
    void unknownMappingFieldsAreRejected() throws Exception {
        Path left = Files.writeString(dir.resolve("l.csv"), "date,amount\n2026-01-01,1\n");
        Path mapping = Files.writeString(dir.resolve("m.json"), "{\"date\":\"date\",\"amount\":\"amount\",\"amout\":\"x\"}");
        Run result = run("match", left.toString(), left.toString(), "--left-mapping", mapping.toString());
        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).contains("amout");
    }
}
