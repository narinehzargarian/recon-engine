# Examples

The same bank statement in two formats, to reconcile against `ledger.csv`.

```sh
java -jar recon.jar match ledger.csv bank.ofx \
  --tolerance 0.25 --fee-percent 2.9 --fee-fixed 0.30

java -jar recon.jar match ledger.csv bank-debit-credit.csv \
  --right-mapping bank-debit-credit-mapping.json --tolerance 0.25 --fee-percent 2.9 --fee-fixed 0.30
```

What to expect:

| Ledger | Bank | Why |
|---|---|---|
| L1, office supplies | ACME OFFICE SUP | One day apart, so a fuzzy match |
| L2, invoice INV-2041 | NORTHSTAR FREIGHT | 25 cents less after a wire fee, within the tolerance |
| L3, L4, L5, three card sales | STRIPE PAYOUT 197.67 | One payout covering three sales, less 2.9% + 30 cents each |
| L6, check 1042 | CHECK 1042 | Matched by check number |
| L7, telecom bill | LUMEN TELECOM | Two days apart |
| L8, check 1043 | (none) | Not cashed yet, so left unmatched |
| (none) | MONTHLY SERVICE FEE | Not in the ledger yet, so left unmatched |
