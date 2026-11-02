package io.github.narinehzargarian.recon.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.narinehzargarian.recon.MatchConfig;
import io.github.narinehzargarian.recon.MatchType;
import io.github.narinehzargarian.recon.Reconciler;
import io.github.narinehzargarian.recon.Transaction;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ReadersTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "1234.56|USD|false|123456",
        "-1,234.56|USD|false|-123456",
        "$1,234.56|USD|false|123456",
        "(12.00)|USD|false|-1200",
        "12.00-|USD|false|-1200",
        "+5|USD|false|500",
        "USD 7.5|USD|false|750",
        "1.234,56|EUR|true|123456",
        "-0,99 €|EUR|true|-99",
        "1500|JPY|false|1500",
        ".5|USD|false|50",
    })
    void parsesRealWorldAmounts(String text, String currency, boolean decimalComma, long expected) {
        assertThat(Amounts.parseMinor(text, currency, decimalComma)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"abc|USD", "''|USD", "1.234|USD", "1.5|JPY", "1..2|USD", "--|USD"})
    void rejectsBadAmounts(String text, String currency) {
        assertThatThrownBy(() -> Amounts.parseMinor(text, currency, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsCsvWithCustomColumnsAndKeepsGoodRowsWhenSomeAreBad() throws Exception {
        String csv = """
                Posted Date,Details,Debit,Credit,Check Number
                03/02/2026,ACME OFFICE,45.10,,
                03/03/2026,CLIENT DEPOSIT,,"1,200.00",
                not a date,BROKEN,1.00,,
                03/04/2026,BOTH FILLED,1.00,2.00,
                03/05/2026,CHECK,250.00,,1042
                """;
        var mapping = new CsvMapping(null, "posted date", "MM/dd/yyyy", null, "debit", "credit", "details", "check number",
                null, "USD", false, false, ',');
        var result = new CsvTransactionReader(mapping).read(new StringReader(csv), 1000);
        assertThat(result.transactions()).extracting(Transaction::amountMinor).containsExactly(-4510L, 120000L, -25000L);
        assertThat(result.transactions().get(2).reference()).isEqualTo("1042");
        assertThat(result.transactions().get(0).date()).isEqualTo(LocalDate.of(2026, 3, 2));
        assertThat(result.errors()).hasSize(2);
        assertThat(result.errors().get(0).line()).isEqualTo(4);
        assertThat(result.errors().get(0).message()).contains("doesn't match MM/dd/yyyy");
        assertThat(result.errors().get(1).message()).contains("both debit and credit");
    }

    @Test
    void csvHeaderMatchingIgnoresCaseSpacesAndByteOrderMark() throws Exception {
        String csv = "﻿Transaction_ID,DATE,Amount\nt1,2026-01-05,9.99\n";
        var result = new CsvTransactionReader(new CsvMapping("transaction id", "date", null, "amount", null, null, null, null, null, null, false, false, ','))
                .read(new StringReader(csv), 10);
        assertThat(result.transactions()).singleElement().satisfies(t -> assertThat(t.id()).isEqualTo("t1"));
    }

    @Test
    void missingColumnGivesAHelpfulError() {
        assertThatThrownBy(() -> new CsvTransactionReader(CsvMapping.standard()).read(new StringReader("when,how much\n2026-01-01,1\n"), 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("column 'date' not found");
    }

    @Test
    void csvRowLimitIsEnforced() throws Exception {
        String csv = "date,amount\n2026-01-01,1\n2026-01-02,2\n2026-01-03,3\n";
        var result = new CsvTransactionReader(CsvMapping.standard()).read(new StringReader(csv), 2);
        assertThat(result.transactions()).hasSize(2);
        assertThat(result.errors()).singleElement().satisfies(error -> assertThat(error.message()).contains("stopped after 2"));
    }

    @Test
    void readsOfxVersion1() {
        String ofx = """
                OFXHEADER:100
                DATA:OFXSGML
                VERSION:102

                <OFX>
                <BANKMSGSRSV1><STMTTRNRS><STMTRS>
                <CURDEF>USD
                <BANKTRANLIST>
                <STMTTRN>
                <TRNTYPE>DEBIT
                <DTPOSTED>20260302120000.000[-5:EST]
                <TRNAMT>-45.10
                <FITID>2026030201
                <NAME>ACME OFFICE &amp; SUPPLY
                <MEMO>POS PURCHASE
                <STMTTRN>
                <TRNTYPE>CHECK
                <DTPOSTED>20260305
                <TRNAMT>-250.00
                <FITID>2026030502
                <CHECKNUM>1042
                <NAME>CHECK 1042
                </BANKTRANLIST>
                </STMTRS></STMTTRNRS></BANKMSGSRSV1>
                </OFX>
                """;
        var result = new OfxTransactionReader().read(ofx, 100);
        assertThat(result.errors()).isEmpty();
        assertThat(result.transactions()).hasSize(2);
        Transaction first = result.transactions().get(0);
        assertThat(first.id()).isEqualTo("2026030201");
        assertThat(first.amountMinor()).isEqualTo(-4510);
        assertThat(first.date()).isEqualTo(LocalDate.of(2026, 3, 2));
        assertThat(first.description()).isEqualTo("ACME OFFICE & SUPPLY POS PURCHASE");
        assertThat(result.transactions().get(1).reference()).isEqualTo("1042");
    }

    @Test
    void readsOfxVersion2Xml() {
        String ofx = """
                <?xml version="1.0" encoding="UTF-8"?>
                <?OFX OFXHEADER="200" VERSION="220"?>
                <OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><CURDEF>EUR</CURDEF><BANKTRANLIST>
                <STMTTRN><TRNTYPE>CREDIT</TRNTYPE><DTPOSTED>20260110</DTPOSTED><TRNAMT>1200.00</TRNAMT><FITID>A1</FITID><NAME>Client</NAME></STMTTRN>
                <STMTTRN><TRNTYPE>DEBIT</TRNTYPE><DTPOSTED>2026BAD</DTPOSTED><TRNAMT>-5</TRNAMT><FITID>A2</FITID><NAME>Broken</NAME></STMTTRN>
                </BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>
                """;
        var result = new OfxTransactionReader().read(ofx, 100);
        assertThat(result.transactions()).singleElement().satisfies(t -> {
            assertThat(t.currency()).isEqualTo("EUR");
            assertThat(t.amountMinor()).isEqualTo(120000);
        });
        assertThat(result.errors()).hasSize(1);
    }

    @Test
    void rejectsFilesThatAreNotOfx() {
        assertThatThrownBy(() -> new OfxTransactionReader().read("date,amount\n", 10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportProtectsSpreadsheetsFromFormulas() throws Exception {
        var left = List.of(Transaction.of("L1", LocalDate.of(2026, 1, 1), 100, "=HYPERLINK(\"http://evil\")"));
        var result = new Reconciler(MatchConfig.defaults()).reconcile(left, List.of());
        StringWriter out = new StringWriter();
        ReportWriter.writeCsv(result, out);
        assertThat(out.toString()).contains("'=HYPERLINK").doesNotContain(",=HYPERLINK");
    }

    @Test
    void endToEndFromCsvFilesToReport() throws Exception {
        String ledger = "id,date,amount,description\nL1,2026-02-01,-120.00,Payment to Lumen Telecom\nL2,2026-02-03,50.00,Card sale\nL3,2026-02-03,25.00,Card sale\n";
        String bank = "id,date,amount,description\nB1,2026-02-02,-120.00,LUMEN TELECOM 55312\nB2,2026-02-05,75.00,STRIPE PAYOUT\nB3,2026-02-06,-9.00,MONTHLY FEE\n";
        var reader = new CsvTransactionReader(CsvMapping.standard());
        var result = new Reconciler(MatchConfig.defaults()).reconcile(
                reader.read(new StringReader(ledger), 100).transactions(), reader.read(new StringReader(bank), 100).transactions());
        assertThat(result.summary().matchesByType()).containsEntry(MatchType.FUZZY, 1).containsEntry(MatchType.GROUP, 1);
        assertThat(result.unmatchedRight()).extracting(Transaction::id).containsExactly("B3");
        StringWriter out = new StringWriter();
        ReportWriter.writeCsv(result, out);
        assertThat(out.toString().lines()).hasSize(4).first().asString().startsWith("status,match_type");
        assertThat(out.toString()).contains("matched,GROUP,L2 L3,B2,0.00,0.00,2,false,,,USD,");
    }
}
