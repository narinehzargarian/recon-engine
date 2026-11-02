# Contributing

Thanks for helping.

## Bank files that don't read correctly

This is the most useful kind of report. Please open an issue with:

- The bank, and whether the file is CSV, OFX, or QFX.
- The header row, and two or three rows with the amounts, names, and account numbers replaced with made-up values.
- What you expected, and what happened.

Please never post a real statement.

## Code changes

1. Open an issue first for anything bigger than a small fix, so we can agree on the approach.
2. Add a test that fails without your change.
3. Run `mvn verify`. It needs Docker, for the API's PostgreSQL tests.

Some guidelines:

- Money stays in whole minor units (`long`), never `double`.
- The same input must always give the same output, so avoid depending on hash or thread order.
- If a change affects matching, run `AccuracyAndScaleTest` before and after, and include both results.
