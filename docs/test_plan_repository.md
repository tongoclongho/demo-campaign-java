# Repository Test Plan

Scope: `CampaignRepository`, `CampaignUserRepository`, `ClaimCampaignRepository`, plus the schema and
auditing behaviour they depend on.

## Slice and conventions

Follow the existing `ClaimCampaignRepositoryTest` stub:

```java
@Import(TestcontainersConfiguration.class)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ClaimCampaignRepositoryTest { … }
```

**Docker must be running** — this starts real `postgres:latest` and `redis:latest` containers.
Nothing here should be tested against H2: three of the four queries are `nativeQuery = true`, so an
in-memory substitute would not exercise the SQL that actually ships.

Four things to know before writing cases:

- **Flyway seeds the database.** `V2__create_test_data.sql` inserts 7 `campaign_user` rows and 2
  campaigns (`BLACK_FRIDAY` capacity 5, `HAPPY_FRIDAY` capacity 3). Assert relative counts
  (`countBefore + 1`) or filter by ids you inserted — never assert an absolute table count.
- **`@DataJpaTest` is transactional** and rolls back after each test, so inserts do not leak between
  tests. Use `TestEntityManager.flush()` to force constraint violations to surface inside the test.
- **Auditing is off in this slice.** `@EnableJpaAuditing` and the `AuditorAware` bean live in
  `MyConfig`, a `@Configuration` that `@DataJpaTest` does not scan. Importing `MyConfig` directly
  fails, because its `ApplicationRunner` bean needs `CacheManager`. Add a local
  `@TestConfiguration` carrying only `@EnableJpaAuditing` for the auditing cases below.
- **There are no foreign keys.** `claim_campaign.campaign_id` / `user_id` are plain `bigint` columns,
  so orphan claims insert cleanly. That is by design here, and RP-C4 pins it.

## CampaignRepository

| ID | Scenario | Assert |
| --- | --- | --- |
| RP-CR1 | `existingCampaignCode` for a seeded code | `existingCampaignCode("BLACK_FRIDAY")` returns 1 |
| RP-CR2 | unknown code | returns 0 |
| RP-CR3 | **case sensitivity** | `existingCampaignCode("black_friday")` returns 0. The column is case-sensitive, which is why `CampaignServiceImpl` uppercases before calling — pins the contract the service relies on (see CS-C3 in the service plan) |
| RP-CR4 | duplicate code rejected by the DB | insert a second campaign with an existing code, `flush()` → `DataIntegrityViolationException` (`uk_campaign_code`) |
| RP-CR5 | id is generated on save | save a campaign with a null id → id is non-null afterwards, confirming `GenerationType.IDENTITY` works against the `DEFAULT nextval('campaign_seq')` column |
| RP-CR6 | snake_case mapping | `totalCapacity` round-trips, proving the physical naming strategy maps it to `total_capacity` |

## CampaignUserRepository

| ID | Scenario | Assert |
| --- | --- | --- |
| RP-U1 | seeded user loads | `findById` of a seeded user returns it with the seeded `loyaltyPoints` |
| RP-U2 | duplicate email | second insert of `alice@campaign.com` + `flush()` → `DataIntegrityViolationException` (`uk_user_email`) |
| RP-U3 | `loyaltyPoints` default | insert without points → column default `0` applies, or the entity value is persisted when set |

## ClaimCampaignRepository — `findByUserIdAndCampaignId`

| ID | Scenario | Assert |
| --- | --- | --- |
| RP-F1 | match exists | returns the claim with the expected status |
| RP-F2 | no match | returns `Optional.empty()` |
| RP-F3 | **arguments not transposed** | insert `(userId=1, campaignId=2)`; `findByUserIdAndCampaignId(2L, 1L)` returns empty while `(1L, 2L)` returns the row — both params are `Long`, so a swap is invisible to the compiler |
| RP-F4 | multiple claims by the same user across campaigns | only the row for the requested campaign is returned |

## ClaimCampaignRepository — `getClaimCampaignBy` (native)

| ID | Scenario | Assert |
| --- | --- | --- |
| RP-G1 | filters by campaign and status | 3 claims on one campaign (APPROVED/SUBMITTED/REJECTED) → `getClaimCampaignBy(id, "APPROVED")` returns only the approved one |
| RP-G2 | scoped to the campaign | approved claims on two campaigns → only the requested campaign's rows come back |
| RP-G3 | **enum stored as its name** | persist a claim with `status = APPROVED`, then query with the literal `"APPROVED"` → found. This is what makes `@Enumerated(EnumType.STRING)` load-bearing; switching to ordinal storage would break `CacheManager` silently |
| RP-G4 | unknown status string | returns an empty list, no exception |
| RP-G5 | no claims for the campaign | returns an empty list |

## ClaimCampaignRepository — `aggregateCampaignUsedSeats` (native, record projection)

The highest-risk query in the repo: a native query whose aliases must bind to the `CampaignUsedSeats`
record components.

| ID | Scenario | Assert |
| --- | --- | --- |
| RP-A1 | counts approved only | campaign with 2 APPROVED + 1 SUBMITTED + 1 REJECTED → `usedSeats == 2` |
| RP-A2 | groups per campaign | two campaigns with different approved counts → one row each, counts matched to the right id |
| RP-A3 | campaign with zero approved claims | **no row** is returned for it (not a row with 0) — this is exactly why `CampaignServiceImpl` defaults `usedSeats` to 0 rather than reading it from the projection |
| RP-A4 | **empty id set** | call with `Set.of()` → empty list, no exception. Hibernate expands the empty collection to `IN (null)`, not `IN ()`. See *Known gaps* — this was predicted to fail and does not |
| RP-A5 | **projection binding** | assert `campaignId()` and `usedSeats()` are both non-null and correct. Postgres folds unquoted aliases to lower case (`campaignId` → `campaignid`), so this case is what catches a projection that silently binds nulls |
| RP-A6 | subset of ids | pass only one of two campaign ids → a single row for that id |

## Schema and entity behaviour

| ID | Scenario | Assert |
| --- | --- | --- |
| RP-C1 | auditing populates timestamps | with the local `@EnableJpaAuditing` test config, a saved entity has non-null `created`/`lastModified` |
| RP-C2 | `lastModified` advances on update | save, flush, modify, flush → `lastModified` is later than `created` |
| RP-C3 | auditing absent without the config | documents that the plain slice leaves `created` null, so RP-C1's import is required rather than incidental |
| RP-C4 | orphan claim inserts | claim with `campaign_id = 999999` (nonexistent) saves without error — no FK constraint exists |
| RP-C5 | nullable claim columns | claim with null `reason` persists and reads back null |
| RP-C6 | Flyway migrations applied | `campaign`, `campaign_user`, `claim_campaign` all exist and the seeded rows are present — a smoke test that catches a broken migration early |

## Known gaps this plan documents

Both predicted gaps have now been executed against `postgres:18` via Testcontainers, and **neither
is real**. Kept here with their outcomes so the reasoning is not re-derived later.

1. **`aggregateCampaignUsedSeats` with an empty set** (RP-A4) — *not a defect*. The prediction was
   that `IN :campaignIds` with an empty collection renders `IN ()`, a Postgres syntax error. In fact
   Hibernate expands it to `IN (null)`: valid SQL that matches nothing, so the call returns an empty
   list. `CampaignServiceImpl.getCampaigns()` calling it unconditionally with `map.keySet()` is safe
   on an empty `campaign` table; no early return is needed.
2. **Alias case folding** (RP-A5) — *not a defect*. Postgres does fold the unquoted aliases to
   `campaignid` / `usedseats`, but Spring Data's tuple-to-record conversion still binds both
   components. No need to quote the aliases (`AS "campaignId"`) or rename them.
3. ~~`ClaimCampaignRepositoryTest` contains one empty stub method
   (`findByName_from_existingCustomer_then_OK`)~~ — replaced by the cases above.
