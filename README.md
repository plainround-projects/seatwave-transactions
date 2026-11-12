# seatwave-transactions

SeatWave's real PostgreSQL schema (seven tables, a partial unique index and a trigger that hold the course's invariant), the hold and booking transactions written against it, a deliberately broken twin of those transactions, and the tests that race both on PostgreSQL 18.6 to show which isolation level, lock and constraint stops which anomaly. This is the project of **Module 13, "Tables and transactions"**, of the course System Design Fundamentals; the running case is SeatWave, a ticket-booking service.

## What it shows

- The double booking happens without the unique claim index (200 racers, 200 holds on seat A-7) and cannot happen with it (1 hold, 199 refused), and the production `HoldStore` answers the 199 with "seat taken" instead of an error.
- The fifth hold of one customer is refused by the trigger `hold_limit_per_customer`, which locks the customer's row and then counts; a fifth hold started while the fourth is still uncommitted waits for that lock and is then refused.
- A lost update and a write skew behave at each isolation level as PostgreSQL's isolation table says: the lost update happens at read committed and is aborted with 40001 at repeatable read and serializable; the write skew happens at read committed and repeatable read (even with a lock on the customer row, at repeatable read) and is aborted only at serializable, whose retries keep the limit.
- Two group holds that lock seats in opposite orders deadlock, and PostgreSQL aborts one with 40P01 after `deadlock_timeout`; in one lock order they never do.
- How long the hold transaction takes, how long a second fan on the same seat waits, and how many writes a second one hot row can take with a row lock, a version column and an in-place update.

## Prerequisites

- JDK 25 (the build's toolchain asks for 25; it was run on Eclipse Temurin 25.0.2).
- Docker with Compose v2 for the stack (run on Docker Desktop, engine 24.0.6, Compose v2.23.0-desktop.1). `./gradlew check` needs no Docker.
- `openssl` on the machine, for the generated lab passwords.

## Dependencies

Every version is pinned in `gradle/libs.versions.toml`, transitive versions in `gradle.lockfile`; all were checked on Maven Central on 11 November 2026 and again on 12 November 2026, and OSV listed no vulnerability for any of them on either day.

| Coordinates | Version | Checked | License | Why the JDK alone is not enough |
|---|---|---|---|---|
| `org.postgresql:postgresql` | 42.7.14 | 12 Nov 2026 | BSD-2-Clause | The JDK has the JDBC interfaces, not a driver for PostgreSQL's protocol; `SqlStates` reads the constraint name from its error. |
| `com.zaxxer:HikariCP` | 7.1.0 | 12 Nov 2026 | Apache-2.0 | A connection pool: 50 racers need 50 connections at once. |
| `org.flywaydb:flyway-core`, `org.flywaydb:flyway-database-postgresql` | 13.12.1 | 12 Nov 2026 | Apache-2.0 | Numbered migrations, applied once each, with checksums. |
| `com.github.sviperll:result4j` | 1.2.0 | 12 Nov 2026 | Apache-2.0 | `Result` for expected refusals. |
| `org.slf4j:slf4j-api`, `ch.qos.logback:logback-classic` (runtime) | 2.0.20, 1.6.6 | 12 Nov 2026 | MIT; EPL-1.0 or LGPL-2.1 | HikariCP and Flyway log through SLF4J. |
| `org.junit:junit-bom`, `org.assertj:assertj-core`, `org.awaitility:awaitility` (test) | 6.1.3, 3.27.7, 4.3.0 | 12 Nov 2026 | EPL-2.0, Apache-2.0, Apache-2.0 | Tests, assertions, waiting for a lock wait without sleeping. |
| Spotless, google-java-format, JaCoCo (build) | 8.11.0, 2.0.0, 0.8.16 | 12 Nov 2026 | Apache-2.0, Apache-2.0, EPL-2.0 | Enforced format and a 0.80 line-coverage floor. |

Image: `postgres:18.6` (Docker Official Image, Debian trixie), pinned by tag and digest in `compose.yaml`, checked on Docker Hub on 11 and 12 November 2026, with an `arm64` variant. Gradle 9.9.1 through the wrapper; its jar and distribution checksums match services.gradle.org.

## Commands and what they printed

Run on 12 November 2026 on an Apple M2 Max (12 cores, 32 GB, macOS 27.2) with other programs running; PostgreSQL in Docker Desktop's Linux virtual machine (12 CPUs, 8 GB), so every timing includes the round trips from the Mac into that machine. The timed runs were taken under the course's shared measurement lock; the 1-minute load average was 4.54 when they began and 7.44 when they ended. Your numbers will differ.

```bash
./gradlew clean check                 # format, compile with warnings as errors, unit tests, coverage
scripts/stack.sh test --run --yes     # PostgreSQL up, the stack tests and their coverage, down -v
scripts/stack.sh up --run --yes       # for the programs below
./gradlew -q raceDemo                 # the races on plain Java objects (needs no database)
./gradlew -q holdTiming               # the hold transaction, timed
./gradlew -q priceRace                # Lab 2: one hot row, three fixes
scripts/stack.sh down --run --yes     # down -v, delete the passwords, check nothing is left
```

Every `scripts/stack.sh` action is a dry run without `--run` (a dry run needs no Docker and changes nothing), asks before it changes anything unless `--yes` is given, touches only the Compose project `seatwave-transactions`, and appends an audit line to `build/stack/audit.log`. `up` writes three throwaway passwords into `.secrets/` (ignored by git), readable by every account on the machine: on a Linux host a Compose secret is a bind mount that keeps the file's mode, and the container's `postgres` user reads it. `down` deletes them with the database. `holdTiming` deletes every hold, booking and customer of the lab schema `seatwave` before it starts; it accepts only a database on `127.0.0.1` in ports 21300 to 21399.

```text
$ ./gradlew clean check
(the run printed 60 lines that end in PASSED, and none that end in FAILED or SKIPPED; these are its last lines)

BUILD SUCCESSFUL in 3s
10 actionable tasks: 10 executed
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.9.1/userguide/configuration_cache_enabling.html
```

45 unit test methods, 60 runs with the parameterized cases; line coverage 0.918 (270 of 294 lines) of the code that needs no database. The test task also ran 40 times in a row beside 8 busy loops on the 12 cores (40 runs, 0 failed), and `./gradlew clean check` passed in a copy of the repository at a path of 404 characters and in one whose folder name holds a space.

```text
$ scripts/stack.sh test --run --yes
plan: test the Compose project seatwave-transactions
  up, then ./gradlew stackTest stackCoverage, then down -v (always)
 Network seatwave-transactions_default  Creating
 Network seatwave-transactions_default  Created
 Volume "seatwave-transactions_pgdata"  Creating
 Volume "seatwave-transactions_pgdata"  Created
 Container seatwave-transactions-postgres-1  Creating
 Container seatwave-transactions-postgres-1  Created
 Container seatwave-transactions-postgres-1  Starting
 Container seatwave-transactions-postgres-1  Started
 Container seatwave-transactions-postgres-1  Waiting
 Container seatwave-transactions-postgres-1  Healthy
> Task :compileJava UP-TO-DATE
> Task :processResources UP-TO-DATE
> Task :classes UP-TO-DATE
> Task :compileTestJava UP-TO-DATE
> Task :processTestResources UP-TO-DATE
> Task :testClasses UP-TO-DATE

> Task :stackTest

ConditionalUpdateStackTest > exactlyOneOfTwoHundredFansHoldsTheSeat() PASSED

DeadlockStackTest > theOneLockOrderMakesTheSecondFanWaitInsteadOfDeadlocking() PASSED

DeadlockStackTest > oppositeOrdersDeadlockAndTheDatabaseAbortsOne() PASSED

DoubleBookingStackTest > checkThenWriteWithoutTheGuardSellsOneSeatToEveryRacer() PASSED

DoubleBookingStackTest > theUniqueIndexRefusesEveryClaimButOneWhenTheCodeIsBroken() PASSED

DoubleBookingStackTest > theProductionHoldGrantsTheSeatOnceAndRefusesTheRestAsTaken() PASSED

DoubleBookingStackTest > countThenInsertAtRepeatableReadLetsOneCustomerHoldEverySeatItAsks() PASSED

DoubleBookingStackTest > theProductionHoldKeepsOneCustomerToFourActiveHolds() PASSED

HoldStoreStackTest > confirmsAnActiveHoldAtTheSectionPrice() PASSED

HoldStoreStackTest > refusesASeatTheEventDoesNotHave() PASSED

HoldStoreStackTest > holdsNoSeatOfAGroupWhenOneIsTaken() PASSED

HoldStoreStackTest > refusesASecondBookingOfASoldSeatEvenWrittenByHand() PASSED

HoldStoreStackTest > makesAConcurrentFifthHoldWaitForTheFourthAndThenRefusesIt() PASSED

HoldStoreStackTest > refusesAHoldWrittenAtRepeatableRead() PASSED

HoldStoreStackTest > releasesOnceAndThenChangesNothing() PASSED

HoldStoreStackTest > refusesToConfirmAnExpiredHold() PASSED

HoldStoreStackTest > holdsEverySeatOfAGroupInOneTransaction() PASSED

HoldStoreStackTest > claimsTheSeatsOfAGroupInLockOrder() PASSED

HoldStoreStackTest > neverLetsTheApplicationRoleDeleteABooking() PASSED

HoldStoreStackTest > letsAnotherFanTakeASeatWhoseHoldHasExpired() PASSED

HoldStoreStackTest > refusesToConfirmTheSameHoldTwice() PASSED

HoldStoreStackTest > refusesToConfirmAnotherFansHold() PASSED

LostUpdateStackTest > theSameReadModifyWriteKeepsBothRaisesAtAStrongerLevel(IsolationLevel) > [1] REPEATABLE_READ PASSED

LostUpdateStackTest > theSameReadModifyWriteKeepsBothRaisesAtAStrongerLevel(IsolationLevel) > [2] SERIALIZABLE PASSED

LostUpdateStackTest > aVersionColumnKeepsEveryRaise() PASSED

LostUpdateStackTest > readModifyWriteLosesOneRaise() PASSED

LostUpdateStackTest > anInPlaceUpdateKeepsEveryRaise() PASSED

LostUpdateStackTest > aRowLockKeepsEveryRaise() PASSED

WriteSkewStackTest > theSameLockAtRepeatableReadStillBreaksTheLimit() PASSED

WriteSkewStackTest > readCommittedWithoutALockBreaksTheLimit() PASSED

WriteSkewStackTest > serializableKeepsTheLimitByAbortingAndRetrying() PASSED

WriteSkewStackTest > aLockOnTheCustomerRowAtReadCommittedKeepsTheLimit() PASSED

> Task :test UP-TO-DATE
> Task :jacocoTestReport UP-TO-DATE
> Task :stackCoverageReport
> Task :stackCoverage

BUILD SUCCESSFUL in 10s
9 actionable tasks: 3 executed, 6 up-to-date
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.9.1/userguide/configuration_cache_enabling.html
 Container seatwave-transactions-postgres-1  Stopping
 Container seatwave-transactions-postgres-1  Stopped
 Container seatwave-transactions-postgres-1  Removing
 Container seatwave-transactions-postgres-1  Removed
 Volume seatwave-transactions_pgdata  Removing
 Network seatwave-transactions_default  Removing
 Volume seatwave-transactions_pgdata  Removed
 Network seatwave-transactions_default  Removed
nothing of seatwave-transactions is left
exit status 0
```

31 stack test methods, 32 runs; line coverage 0.930 (611 of 657 lines) of all code but the three programs, over both test runs. The stack test task also ran 40 times in a row beside 8 busy loops (40 runs, 0 failed); the serializable limit race needed 122 to 176 aborts per run, every one retried, and always ended with 4 holds. 23 of 23 one-line mutants of the code, the schema and the settings were each caught by a failing test; the list is in Module 13, Section 8.

```text
$ ./gradlew -q raceDemo
seat race: 200 fans, one seat A-7
  check, then write      granted 200, refused   0, holds on A-7 200, double bookings 199
  conditional insert     granted   1, refused 199, holds on A-7   1, double bookings   0
limit race: 50 requests of one customer, limit 4
  count, then insert     granted  50, refused   0, active holds  50, over the limit by  46
  lock, count, insert    granted   4, refused  46, active holds   4, over the limit by   0
lost update: two raises of 5.00 on a price of 80.00
  read, add, write       final 85.00, expected 90.00, raises lost 1
  lock, read, add, write final 90.00, expected 90.00, raises lost 0
```

```text
$ ./gradlew -q holdTiming      (three runs)
run 1, load: 4.77 4.70 4.26
hold transaction, nobody else on the seat (2000 holds): p50 1.42 ms, p90 1.63 ms, p99 2.12 ms, max 6.36 ms, mean 1.46 ms
two fans on one seat (1000 pairs), the winner: p50 1.61 ms, p90 1.82 ms, p99 2.25 ms, max 5.00 ms, mean 1.64 ms
two fans on one seat (1000 pairs), the loser: p50 1.86 ms, p90 2.09 ms, p99 2.54 ms, max 7.15 ms, mean 1.90 ms
exit status 0
run 2, load: 4.62 4.67 4.25
hold transaction, nobody else on the seat (2000 holds): p50 1.35 ms, p90 1.81 ms, p99 2.62 ms, max 19.03 ms, mean 1.47 ms
two fans on one seat (1000 pairs), the winner: p50 1.83 ms, p90 2.09 ms, p99 3.42 ms, max 5.28 ms, mean 1.86 ms
two fans on one seat (1000 pairs), the loser: p50 2.10 ms, p90 2.39 ms, p99 4.03 ms, max 7.70 ms, mean 2.14 ms
exit status 0
run 3, load: 4.46 4.63 4.24
hold transaction, nobody else on the seat (2000 holds): p50 1.43 ms, p90 1.66 ms, p99 2.59 ms, max 28.59 ms, mean 1.52 ms
two fans on one seat (1000 pairs), the winner: p50 1.69 ms, p90 1.97 ms, p99 2.40 ms, max 8.13 ms, mean 1.73 ms
two fans on one seat (1000 pairs), the loser: p50 1.95 ms, p90 2.26 ms, p99 2.72 ms, max 8.40 ms, mean 2.00 ms
exit status 0
```

```text
$ ./gradlew -q priceRace       (three runs)
run 1, load: 5.30 4.81 4.31
16 clerks x 125 raises of 0.01 on one row; expected final price 100.00
  ROW_LOCK        raises written 2000 of 2000,   920.0 per second, version conflicts     0, final 100.00
  VERSION_COLUMN  raises written 2000 of 2000,   126.2 per second, version conflicts 22517, final 100.00
  IN_PLACE        raises written 2000 of 2000,  1371.4 per second, version conflicts     0, final 100.00
exit status 0
run 2, load: 6.98 5.23 4.47
16 clerks x 125 raises of 0.01 on one row; expected final price 100.00
  ROW_LOCK        raises written 2000 of 2000,  1036.4 per second, version conflicts     0, final 100.00
  VERSION_COLUMN  raises written 2000 of 2000,   128.0 per second, version conflicts 22001, final 100.00
  IN_PLACE        raises written 2000 of 2000,  1220.0 per second, version conflicts     0, final 100.00
exit status 0
run 3, load: 7.59 5.48 4.58
16 clerks x 125 raises of 0.01 on one row; expected final price 100.00
  ROW_LOCK        raises written 2000 of 2000,   987.6 per second, version conflicts     0, final 100.00
  VERSION_COLUMN  raises written 2000 of 2000,   147.0 per second, version conflicts 24130, final 100.00
  IN_PLACE        raises written 2000 of 2000,  1714.7 per second, version conflicts     0, final 100.00
exit status 0
```

The flush a commit waits for, inside the container, on the data volume:

```text
$ docker compose -p seatwave-transactions exec postgres pg_test_fsync -s 2 -f /var/lib/postgresql/pg_test_fsync.tmp
Compare file sync methods using one 8kB write:
(in "wal_sync_method" preference order, except fdatasync is Linux's default)
        open_datasync                     15778.602 ops/sec      63 usecs/op
        fdatasync                         13997.870 ops/sec      71 usecs/op
        fsync                             12265.936 ops/sec      82 usecs/op
        fsync_writethrough                              n/a
        open_sync                         12972.410 ops/sec      77 usecs/op
```

These are laptop numbers: the flush in Docker Desktop's virtual machine is far faster than a cloud SSD's 1 ms, which the module's estimates for its reference machine assume.

After everything, nothing of the project is left:

```text
$ docker ps -a --filter label=com.docker.compose.project=seatwave-transactions --quiet | wc -l
0
$ docker network ls --filter label=com.docker.compose.project=seatwave-transactions --quiet | wc -l
0
$ docker volume ls --filter label=com.docker.compose.project=seatwave-transactions --quiet | wc -l
0
```

## Ports

One: PostgreSQL on `127.0.0.1:21300`, from Module 13's range 21300 to 21399.

## Project structure

See `ARCHITECTURE.md` for packages, the dependency rule, a data-flow diagram, walk-throughs, decisions and invariants.

```text
compose.yaml, db/init/10-roles.sh, config/stack.properties, scripts/stack.sh
src/main/resources/db/migration/   V1, V2, V3, R__app_grants.sql (the schema later modules build on)
src/main/resources/db/seed/        arena-night.sql: event 1, 20 sections, 20,000 seats
src/main/java/io/plainround/seatwave/transactions/
  booking/  values and refusals      store/  HoldStore, Transactions, the broken twin, Database
  race/     Race, Gate               demo/   the in-memory races
  labs/     Labs 1 and 2, HoldTimingCli         config/ SettingsReader, StackSettings
src/test/java/.../                 unit tests; stack/ the tests tagged "stack"
```

## Where things came from

Built from Module 13's code seed. `config/SettingsReader.java` and its test `SettingsReaderTest.java` are copied unchanged, apart from the package, from the Module 9 project `seatwave-proxy` at version 1.0.1; the shape and guardrails of `scripts/stack.sh` follow that project's. The Gradle wrapper files are those of `seatwave-balancer` (Gradle 9.9.1, checksums compared again). `CHANGELOG.md` lists what the build changed in the seed.

## What is simplified

- One PostgreSQL in a 4-CPU, 2 GB container on a laptop; the module's estimates are for a cloud machine with a 1 ms flush, and the laptop's numbers are not production's.
- No HTTP layer: the store is called directly, so Module 7's `idempotency_keys` and Module 10's `holds.idempotency_key` are left out (adding them back is an expand-only migration).
- The lab schema `seatwave_unguarded` exists only to show what the guards prevent.
- The labs and `holdTiming` reset the lab database's rows before they run; a real database is never reset.

## No CI on purpose

This repository has no `.github/` folder and no CI workflow, on purpose: the course's owner verifies every project locally, with the commands above.

## The Compose path

`compose.yaml` holds all the infrastructure under the project name `seatwave-transactions`; `scripts/stack.sh` starts it, tests it and brings it down with `down -v`, then checks that no container, network or volume of the project is left.

## License

MIT, see `LICENSE`.
