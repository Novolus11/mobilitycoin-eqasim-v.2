# MobilityCoin – eqasim-java (student edition)

A [eqasim-java](https://github.com/eqasim-org/eqasim-java) build that contains
**the MobilityCoin policy** on top of a [MATSim](https://www.matsim.org/) Bavaria scenario.
It is meant as a learning codebase: the MobilityCoin (tradable-credit) implementation is self-contained.

## What this scheme does

MobilityCoin is a **tradable credit scheme** for transport emissions:

- Every agent receives an initial budget of *coins*.
- Emitting trips (car, public transport) **cost** coins; active modes (walking, cycling) can
  **earn** coins.
- A single market **price** (EUR per coin) is updated each iteration by a proportional controller
  until the coin market clears at the desired **emission-reduction target**.
- The price enters the agents' mode-choice utility, nudging them towards low-emission travel.

Two features are the focus of this edition:

1. **Heterogeneous coin allocation** – the (fixed) total coin budget can be split between agents
   in different ways: `UNIFORM`, `INCOME` (low income gets more), `ACCESSIBILITY` (poorly
   connected areas get more), `AGE_EXEMPT` (only working-age agents participate) and
   `GRANDFATHERING` (high baseline emitters get more).
2. **Incentive-adaptive allocation** – coins earned from cycling/walking add to the supply, so the
   agency hands out fewer allocated coins to keep the emission cap intact.

See [`docs/MOBILITYCOIN.md`](docs/MOBILITYCOIN.md) for a guided tour of the code.

## Repository layout

```
core/      eqasim core + the MobilityCoin policy (org.eqasim.core.simulation.policies.impl.mobility_coins)
bavaria/   the Bavaria/Munich MATSim scenario and run entry points
pom.xml    Maven multi-module build (core + bavaria)
```

The MobilityCoin code lives in
`core/src/main/java/org/eqasim/core/simulation/policies/impl/mobility_coins/`:

| File | Role |
|------|------|
| `logic/MobilityCoinsMarket.java` | The market: balances, price controller, dynamic allocation (start here) |
| `logic/MobilityCoinsParameters.java` | All tunable parameters (`--moco:...` on the command line) |
| `logic/MobilityCoinsCalculator.java` | Coins gained/lost per trip from modal distances |
| `logic/MobilityCoinsUtilityPenalty.java` | Turns coin cost into a mode-choice utility penalty |
| `logic/MobilityCoinsRoutingPenalty.java` | Per-link penalty used during routing |
| `allocation/*` | The heterogeneous allocation schemes |
| `strategies/RemoveExpensiveTripsStrategy.java` | Optional elastic demand (drop expensive trips) |
| `MobilityCoinsPolicyExtension.java` | Wires everything into the MATSim controler (DI) |

## Requirements

- Java 21+ (JDK 21 or newer)
- Maven 3.8+
- ~16 GB+ RAM for small scenarios (much more for the full Bavaria population)

## Build

```bash
mvn clean package
```

To build a single self-contained jar for the Bavaria scenario:

```bash
mvn -Pstandalone -pl bavaria -am clean package
# -> bavaria/target/bavaria-1.5.0.jar
```

## Input data (synthetic population pipeline)

This repository contains **code only** – it does **not** include the MATSim input files
(network, population, households, transit schedule, facilities). Those are generated separately
with the eqasim **synthetic-population pipeline**, which turns open data (census, travel survey,
OSM, GTFS) into a runnable MATSim scenario.

Once you have generated the Bavaria scenario files (or obtained them from your supervisor), point
the run config at them. Place generated/large files outside version control – the
[`.gitignore`](.gitignore) already excludes `*.xml.gz`, `output*/`, `results/` and `scenario/`.

## Run

```bash
java -Xmx16G -cp bavaria/target/bavaria-1.5.0.jar \
  org.eqasim.bavaria.RunSimulation \
  --config-path /path/to/bavaria_config.xml
```

Enable and configure the MobilityCoin policy from the command line, e.g.:

```bash
java -Xmx16G -cp bavaria/target/bavaria-1.5.0.jar \
  org.eqasim.bavaria.RunSimulation \
  --config-path /path/to/bavaria_config.xml \
  --moco:allocationScheme INCOME \
  --moco:cost_coins_per_gco2 0.001 \
  --moco:reductionPercentage 0.05
```

The policy itself is activated by an `eqasim:policies` parameter set of type `mobilityCoins`
in the MATSim config file. Outputs `mobility_coins.csv` (per-iteration market metrics) and
`wallets.csv` (per-agent balances) are written to the MATSim output directory.

## License

See [`LICENSE`](LICENSE). Based on eqasim-java by the eqasim contributors.
