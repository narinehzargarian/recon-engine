# recon-engine

Matches transactions between two sources, such as a bank statement and an accounting ledger, and tells you what's missing, what's different, and what needs a second look.

Reconciliation is still done by hand in spreadsheets at a lot of businesses. This does the matching for you, including the hard cases: amounts that differ by a few cents, dates that differ by a few days, and card payouts that combine many sales less fees.

There are three ways to use it:

- **Command-line tool:** one jar file. Point it at two files and get a report.
- **Java library:** add it to your own project.
- **REST API:** a self-hosted Spring Boot service that keeps a history of runs in PostgreSQL.

## Quick start

Requires Java 21 or newer. Download `recon.jar` from the [releases page](https://github.com/narinehzargarian/recon-engine/releases), or build it with `mvn package`.

```sh
java -jar recon.jar sample --out demo     # Generates a ledger and a bank statement to try
java -jar recon.jar match demo/ledger.csv demo/bank.csv \
  --tolerance 0.50 --date-window 4 --fee-percent 2.9 --fee-fixed 0.30 --group-window 5 \
  --csv report.csv
```

```
Left:  1000 transactions, 931 matched, 69 unmatched (total 2820.09)
Right: 887 transactions, 838 matched, 49 unmatched (total 2988.37)
Matches: {FUZZY=438, REFERENCE=225, GROUP=51, EXACT=124}
Review: 11 ambiguous matches had an equally good alternative
Review: 9 groups of possible duplicates
```

`report.csv` opens in any spreadsheet, with every match followed by everything left unmatched.

## Your own files

**OFX and QFX**, the download format most US banks offer, are read automatically by file extension.

**CSV** works with any column layout through a small mapping file. For a bank that splits money in and out into two columns:

```json
{
  "date": "Posted Date",
  "dateFormat": "MM/dd/yyyy",
  "debit": "Debit",
  "credit": "Credit",
  "description": "Details",
  "reference": "Check Number"
}
```

```sh
java -jar recon.jar match ledger.csv bank.csv --right-mapping bank-mapping.json
```

| Mapping field | Meaning |
|---|---|
| `date` | Required. Column name, ignoring case and spaces |
| `dateFormat` | Java date pattern. Default `yyyy-MM-dd` |
| `amount` | One signed amount column. Use this, or `debit` and `credit` |
| `debit`, `credit` | Two columns. Debits become negative |
| `id`, `description`, `reference`, `currency` | Optional columns |
| `defaultCurrency` | Used when there's no currency column. Default `USD` |
| `negate` | `true` if the source records money out as positive |
| `decimalComma` | `true` for amounts written like `1.234,56` |
| `delimiter` | Default `,` |

Amounts can be written like `$1,234.56`, `(12.00)`, `12.00-`, or `1.234,56`. Rows that can't be read are reported with their line number and skipped, so one bad row doesn't stop the run. Add `--strict` to fail instead.

The [`examples`](examples) folder has ready-to-run files, including an OFX statement.

## How matching works

Three passes, each working only on what the earlier ones left unmatched:

1. **Shared reference.** Same invoice or check number. The amount and date must still agree, because a shared reference alone could be a partial payment.
2. **One-to-one.** Within the amount tolerance and date window. Description similarity breaks ties.
3. **Groups.** One transaction equals several on the other side, less fees, such as a Stripe payout covering five sales.

A transaction is used at most once. The same input always gives the same output.

| Option | Default | Meaning |
|---|---|---|
| `--tolerance` | `0` | Largest amount difference for one-to-one matches |
| `--date-window` | `3` | Largest date difference, in days |
| `--min-similarity` | off | Minimum description similarity, 0 to 1 |
| `--fee-percent`, `--fee-fixed` | `0` | Fees on each grouped transaction, like `2.9` and `0.30` |
| `--group-window` | `7` | How many days before a payout its sales may fall |
| `--group-tolerance` | `0` | Allowed difference for groups, after fees |
| `--max-group-size` | `6` | Most transactions combined into one group |
| `--no-groups` | | Turns off group matching |

**Exit codes** let it run in scheduled jobs: `0` for success, `1` if something is unmatched and you passed `--fail-on-unmatched`, `2` for unusable input.

## Design decisions

**Exact money.** Amounts are whole cents in a `long`, never floating point, so totals never drift. Fees round half-even.

**Best overall assignment, not first come first served.** When several transactions compete for the same matches, the engine solves the whole cluster with the Hungarian algorithm, so it matches as many as possible and then picks the closest pairs. A greedy pass can leave a transaction unmatched just because its neighbor took the slightly better option. A test shows this case. Very large clusters fall back to greedy, so run time stays predictable.

**Fast on large files.** Each transaction only compares against the narrow amount range it could match, using a sorted index, rather than against every transaction on the other side.

**Exact group matching by default.** With a tolerance, many wrong combinations of sales come within a few cents of a payout. Each wrong group then takes sales that belong to the next payout, and errors cascade. In testing, requiring exact totals after fees raised precision from about 90% to about 98%. Group tolerance is a separate setting, for processors that round differently.

**Flags instead of guesses.** A match with an equally good alternative is marked ambiguous, and same-day, same-amount, same-description lines are reported as possible duplicates.

**Safe reports.** Text that a spreadsheet would run as a formula, like `=HYPERLINK(...)`, is escaped in CSV output.

## Accuracy

Measured on generated data with a known answer: 2,000 ledger rows with posting delays, small amount differences, reworded descriptions, card payouts less fees, uncleared items, bank-only fees, and duplicates.

| Data | Precision | Recall | Time |
|---|---|---|---|
| 2,000 rows, 5 seeds | 97.7% to 98.6% | 96.1% to 97.6% | under 0.2 s |
| 60,000 ledger and 53,126 bank rows | 94.9% | 92.6% | 3.9 s |

These numbers describe my generator's patterns. Your accuracy depends on your data, so check results before trusting them, especially at first. Accuracy drops when many transactions share nearly the same amount within a few days, because amount and date can't tell them apart. References and descriptions help there.

## Java library

Through [JitPack](https://jitpack.io/#narinehzargarian/recon-engine):

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.narinehzargarian.recon-engine</groupId>
  <artifactId>recon-core</artifactId>
  <version>v0.1.0</version>
</dependency>
```

```java
var config = MatchConfig.defaults()
        .withAmountTolerance(50)                       // 50 cents
        .withDateWindow(4)
        .withFees(new BigDecimal("2.9"), 30);          // 2.9% + 30 cents per sale
ReconciliationResult result = new Reconciler(config).reconcile(ledger, bank);
result.unmatchedLeft().forEach(System.out::println);
```

`recon-core` depends only on Apache Commons CSV.

## REST API

```sh
docker compose up -d                                   # PostgreSQL
java -jar recon-api/target/recon-api.jar

curl -F left=@ledger.csv -F right=@bank.csv http://localhost:8080/api/reconciliations
```

| Endpoint | Returns |
|---|---|
| `POST /api/reconciliations` | Runs a reconciliation. Takes files `left`, `right`, and optionally `options` as JSON with `leftMapping`, `rightMapping`, and `config` |
| `GET /api/reconciliations` | Recent runs |
| `GET /api/reconciliations/{id}` | The full result |
| `GET /api/reconciliations/{id}/report.csv` | The spreadsheet report |

Set `DATABASE_URL`, `DATABASE_USER`, and `DATABASE_PASSWORD` for your own database. **The API has no login.** Run it on a private network, or put it behind something that handles authentication.

## Limits

- One currency per match. There's no currency conversion.
- It hasn't been tested against real exports from specific banks yet, only against files built to the OFX spec and common CSV layouts. Please open an issue if your bank's file doesn't read correctly.
- Group search looks at the 40 nearest candidates and gives up past 3 million steps, so a very busy account can miss a group. The summary says when that happens.
- The whole file is held in memory, up to 1 million rows on the command line and 200,000 through the API.

## Building

```sh
mvn verify      # Needs Docker for the API's PostgreSQL tests
```

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE)
