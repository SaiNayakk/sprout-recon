# sprout-recon

Sprout's **daily reconciliation**: every record that should agree with another is compared, and any
difference is a break for a person to look at. It reads each book through its owner's API, never its
database.

| Check | Compares |
|---|---|
| `LEDGER_BALANCED` | the ledger's assets and liabilities |
| `BANK_MATCHES_LEDGER` | the ledger's `sprout:bank` and Sprout's account at Sprout Bank |
| `HOLDS_MATCH_ORDERS` | each customer's held money, and their working orders and open positions |
| `UNSETTLED_MATCHES_ORDERS` | each customer's unsettled money, and their sales not yet settled |
| `TRADES_MATCH_EXCHANGE` | the order service's executions, and the exchange's trades |
| `DEMAT_MATCHES_HOLDINGS` | what the depository holds for each customer, and their delivered shares |
| `SETTLEMENTS_HEALTHY` | no settlement broken or late |

- **Runs once a session**, after midday market time (the previous day has settled by then), and on demand.
- **Money in flight isn't a break.** Records that move compare twice, a moment apart; only differences on both looks are reported.
- **Can't read is not "fine".** A check whose book couldn't be read is an `ERROR`, never a pass.
- Every run and every check's findings are kept.

## Part of Sprout

[Sprout](https://sainayakk.github.io/sprout-platform/) is a simulated brokerage built from scratch as
separate services, each with its own repository and contract. Architecture, environments and test
evidence live in [sprout-platform](https://github.com/SaiNayakk/sprout-platform); this service's API is
[`recon-v1.yaml`](https://github.com/SaiNayakk/sprout-contracts/blob/main/src/main/resources/sprout/contracts/openapi/recon-v1.yaml)
in sprout-contracts. It runs inside the **money** host.

`./mvnw verify` runs the tests on a real Postgres against stand-ins for every book it reads.

## License

MIT
