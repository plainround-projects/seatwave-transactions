# Changelog

All notable changes to this project are written here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-11-12

The first build of Module 13's code seed (54 files), run for the first time against PostgreSQL 18.6.

### Added

- The Gradle wrapper (9.9.1, both checksums compared), lockfiles, README, ARCHITECTURE, this
  changelog and the license.
- `HoldTimingCli` (`./gradlew holdTiming`): times the hold transaction alone and two fans racing
  for one seat, so the module's claim-window estimate is replaced by a measurement.
- Stack tests: a second booking of a sold seat refused by `one_booking_per_seat`; a fifth hold
  forced to start while the fourth is uncommitted, which must wait for the customer's row lock and
  then be refused (the only test that fails when the trigger stops locking); the claim order of a
  group hold; the lost update at repeatable read and serializable, aborted with 40001 and retried.
- Unit tests for `SettingsReader` (from `seatwave-proxy` 1.0.1), `Hold`, `Booking`, the refusals
  and `IsolationLevel`; Awaitility 4.3.0 for the one test that waits for a lock.
- `src/main/resources/logback.xml` at `WARN`, so HikariCP's and Flyway's debug lines no longer
  bury the programs' output.

### Fixed

- The stack tests timed out at their gates on the first run: two application pools of 64
  connections and two owner pools of 2 need 132 connections, and PostgreSQL's default
  `max_connections` is 100. `compose.yaml` now sets 200.
- `refusesAHoldWrittenAtRepeatableRead` called `rollback` on a connection HikariCP had already
  closed: HikariCP counts SQLSTATE 0A000 as a broken connection.
- The lab passwords moved from `build/secrets` to `.secrets`: `./gradlew clean` deleted them while
  the database still used them. They are readable by all (a Linux bind mount keeps the mode) and
  `scripts/stack.sh down` deletes them with the database.
- `PriceRaceCli` called `System.exit` inside its try-with-resources, so its pools were never
  closed; it also failed on a fresh lab database, because nothing migrated or seeded it. The seed
  loader moved from the tests into `Database.seedIfEmpty`, and both programs migrate and seed.
- `Transactions` shared one `SplittableRandom` across threads; the jitter is now drawn under its
  lock.
- `stackCoverage` used only the stack tests' data when the unit tests had not run in the same
  build; it now depends on both runs and has a report task, `stackCoverageReport`.
- The three Lab 2 fix tests raced two clerks with no gate, so a broken fix passed them; they now
  race 8 clerks raising $0.05 25 times each (the same $10.00 in all), and each was mutation-checked.
