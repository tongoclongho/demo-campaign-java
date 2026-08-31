# Service Test Plan

Scope: `CampaignServiceImpl`, `ClaimCampaignServiceImpl`, `RedisServiceHelper`, `CacheManager`.

## Slices and conventions

Two styles, chosen per class:

- **Plain Mockito** (`@ExtendWith(MockitoExtension.class)`, no Spring context) for `CampaignServiceImpl`
  and for the branching logic of `ClaimCampaignServiceImpl`. Collaborators are `@Mock`, including
  `MapperConfig` and `RedisServiceHelper`.
- **Testcontainers Redis** for `RedisServiceHelper` and `CacheManager`, where the point of the test is
  the actual Redis command semantics (`ZADD` score ordering, `SPOP`, `DECR` returning the post-value,
  `SADD` returning 0 on a duplicate). Mocking `StringRedisTemplate` for these would assert our
  assumptions rather than Redis's behaviour, which is where the bugs live.

Two mechanical constraints to respect when writing these:

- `executeClaimCampaignSubmission`, `processClaimCheck` and `processClaim` are `protected`. Tests must
  live in package `com.demo.campaign.service` to invoke them directly.
- `batchSize` is injected with `@Value("${redis.zset.batch-size:100}")` into a field. Under plain
  Mockito it stays `null` and the first `Math.min(...)` unboxes to an NPE — set it with
  `ReflectionTestUtils.setField(service, "batchSize", 100L)` in `@BeforeEach`.

## CampaignServiceImpl

### `createCampaign`

| ID | Scenario | Arrange | Assert |
| --- | --- | --- | --- |
| CS-C1 | code is normalised | request code `"  black_friday  "` | captured entity `code == "BLACK_FRIDAY"` — trim + uppercase |
| CS-C2 | duplicate code rejected | `existingCampaignCode(...)` returns 1 | throws `ExistingCampaignCodeException`; `verify(campaignRepository, never()).save(any())` |
| CS-C3 | duplicate check uses the **normalised** code | request `"black_friday"` | `verify(repo).existingCampaignCode("BLACK_FRIDAY")` — otherwise case-variant duplicates slip past the check and only fail at the DB constraint |
| CS-C4 | capacity carried through | `totalCapacity = 5` | captured entity `totalCapacity == 5` |
| CS-C5 | result is mapped | repo returns saved entity | returns the DTO from `mapper.toDTO`, `verify(mapper).toDTO(saved)` |

### `getCampaigns`

| ID | Scenario | Arrange | Assert |
| --- | --- | --- | --- |
| CS-G1 | campaign with no approved claims | aggregate returns empty list | DTO `usedSeats == 0` |
| CS-G2 | usedSeats merged onto the right campaign | 2 campaigns, aggregate returns `(id=2, 3)` | campaign 2 has `usedSeats == 3`, campaign 1 has `0` — guards the map-join |
| CS-G3 | null `usedSeats` in the projection | aggregate row with `usedSeats == null` | no NPE, DTO stays `0` (the code null-checks this) |
| CS-G4 | aggregate row for an unknown campaign id | aggregate returns id not in the map | ignored, no NPE |
| CS-G5 | **no campaigns at all** | `findAll()` returns empty | `aggregateCampaignUsedSeats` is called with an empty `Set`. In a unit test this passes; the real native `IN :campaignIds` with an empty collection is invalid SQL in Postgres — see the repository plan (RP-A4) and *Known gaps*. |

## ClaimCampaignServiceImpl — `claimCampaign`

Ordering matters here: the two Redis guards run **before** the Postgres lookups, so a full campaign is
rejected without touching the database.

| ID | Scenario | Arrange | Assert |
| --- | --- | --- | --- |
| CL-S1 | happy path persists SUBMITTED | all guards pass, both entities found | captured `ClaimCampaign` has `status == SUBMITTED`, correct `campaignId`/`userId`/`claimDate` |
| CL-S2 | happy path enqueues | as above | `verify(redisServiceHelper).sendClaimToPriorityQueue(captor)`; captured DTO carries `userPoints` **from the loaded user**, not from the request |
| CL-S3 | already a member | `requireUserNotInCampaignMembers` throws | exception propagates; `verify(claimCampaignRepository, never()).save(any())` and no enqueue |
| CL-S4 | no seats | `requireCampaignAvailableSeats` throws | as above — nothing persisted, nothing enqueued |
| CL-S5 | unknown campaign | `campaignRepository.findById` empty | `NoSuchCampaignException`; no save, no enqueue |
| CL-S6 | unknown user | `campaignUserRepository.findById` empty | `NoSuchUserException`; no save, no enqueue |
| CL-S7 | guard order | both guards stubbed to throw | `InOrder` verify: membership check runs before the seat check, and both before `campaignRepository` — a regression here means a full campaign starts hitting Postgres per request |
| CL-S8 | persist happens before enqueue | happy path | `InOrder` verify save → `sendClaimToPriorityQueue`; enqueueing first would make the job pop a claim whose row does not exist yet |

## ClaimCampaignServiceImpl — `processClaim`

The settlement decision. This is the highest-value block in the codebase to cover.

| ID | Scenario | Arrange | Assert |
| --- | --- | --- | --- |
| CL-P1 | seat reserved and member added | `reserveCampaignSeat` → `Optional.of(4L)`, `addUserToCampaignMembers` → `Optional.of(TRUE)` | saved entity `status == APPROVED`, `reason == null` |
| CL-P2 | no seats left | `reserveCampaignSeat` → `Optional.empty()` | `status == REJECTED`, `reason` contains campaign and user id; `addUserToCampaignMembers` never called |
| CL-P3 | duplicate member | reserve OK, `addUserToCampaignMembers` → `Optional.empty()` | `status == REJECTED`, reason mentions already claimed |
| CL-P4 | rejection always persists | either rejection path | `verify(claimCampaignRepository).save(...)` — a rejected claim must not be left at SUBMITTED |
| CL-P5 | **claim row missing** | `findByUserIdAndCampaignId` → `Optional.empty()`, reserve+add succeed | Currently throws NPE out of `processClaim`: the null entity is logged but not returned from, then `setStatus` NPEs inside the `try`, and the `catch` block NPEs again on the same null reference. Assert the current throw, and see *Known gaps*. |
| CL-P6 | seat leak on duplicate member | reserve OK, add fails (CL-P3) | assert `reserveCampaignSeat` was called and no compensating increment happens — the decremented seat is only recovered by the next `refreshRedisCache`. Document, do not "fix" in the test. |

## ClaimCampaignServiceImpl — `processClaimCheck` / `executeClaimCampaignSubmission`

Best run against a real Redis container, since the ordering guarantee is `ZPOPMAX` semantics.

| ID | Scenario | Arrange | Assert |
| --- | --- | --- | --- |
| CL-J1 | nothing queued | id-set empty | `getCampaignIdsForClaimCheck` returns empty; no repository interaction |
| CL-J2 | **earliest claim wins** | enqueue 3 claims for one campaign with claim dates t3 < t1 < t2, capacity 1 | the claim with the earliest date is APPROVED, the other two REJECTED — this is the core fairness property, and it is the one an inverted-score regression would silently break |
| CL-J3 | loyalty points do not affect order | two claims, later date but far higher points | the earlier claim still wins; points ride in the value only |
| CL-J4 | batch smaller than queue | 5 queued, `batchSize = 2` | all 5 processed across loop iterations, queue drained to `ZCARD == 0` |
| CL-J5 | campaign id set is drained | 2 campaigns queued | after the run `claim_campaign_priority_ids_set` is empty (`SPOP` consumed it) |
| CL-J6 | one campaign failing does not block others | make campaign A throw (e.g. CL-P5 conditions), campaign B healthy | B's claims are still settled — `processClaimCheck` catches per campaign |
| CL-J7 | **claims lost on mid-batch failure** | campaign with 3 queued claims, second one triggers the CL-P5 NPE | claims 1 settles, but the popped remainder is gone from Redis and never settled — `popMax` removes before processing. Assert and document; see *Known gaps*. |
| CL-J8 | value parsing | value `"100#42"` | `extractUserId` yields 42; a malformed value (no `#`) yields null and must not abort the batch |

## RedisServiceHelper (real Redis)

| ID | Scenario | Assert |
| --- | --- | --- |
| RS-1 | `requireUserNotInCampaignMembers` | throws when the user is in the set; passes when absent or the key does not exist |
| RS-2 | `requireCampaignAvailableSeats` | throws when key missing, `"0"`, or negative; passes for positive |
| RS-3 | `sendClaimToPriorityQueue` writes both keys | ZSET member is `"{points}#{userId}"`; the campaign id is added to the id-set |
| RS-4 | score inversion | for t1 < t2, `score(t1) > score(t2)` so `ZPOPMAX` yields t1 first |
| RS-5 | `reserveCampaignSeat` | from `"1"` returns `Optional.of(0)`; from `"0"` returns `empty` and leaves the counter at `-1` (documents the overshoot) |
| RS-6 | `addUserToCampaignMembers` | first call returns present, second returns `empty` |
| RS-7 | `getCampaignIdsForClaimCheck` | returns ids and removes them; empty set returns an empty list |
| RS-8 | `refreshCampaignMembersCache` with empty ids | deletes the key rather than leaving stale members |
| RS-9 | `refreshCampaignAvailableSeatsCache` keeps the max | current `"5"`, recomputed `3` → stays `5`; current `"1"`, recomputed `4` → becomes `4` |
| RS-10 | refresh methods swallow failures | with an unreachable template, no exception escapes (they log and continue) |

## CacheManager

| ID | Scenario | Assert |
| --- | --- | --- |
| CM-1 | rebuild from approved rows | 2 approved claims, capacity 5 → member set has both users, seats key is `3` |
| CM-2 | only APPROVED counts | mix of SUBMITTED/REJECTED/APPROVED → only APPROVED users are members and only they consume seats |
| CM-3 | campaign with no claims | member set key deleted, seats == `totalCapacity` |
| CM-4 | startup runner invokes it | `@SpringBootTest` with containers: after startup, seats keys exist for the seeded campaigns |

## Known gaps this plan documents

Assert current behaviour so the suite stays green; update the case if the code is fixed.

1. **`processClaim` NPEs when the claim row is missing** (CL-P5) and the NPE escapes, killing the rest
   of the batch (CL-J7). The missing-row branch logs a warning but does not `return`, and the `catch`
   block dereferences the same null. Fix: return early after the warning.
2. **Popped claims are lost on failure** (CL-J7). `ZPOPMAX` removes entries before they are settled, so
   any throw discards them. Only the 60s `refreshRedisCache` restores consistency, and it does not
   re-settle the orphaned SUBMITTED rows.
3. **Seat leak on a rejected claim** (CL-P6, RS-5). `DECR` is not compensated on rejection; the counter
   can go negative and is only corrected by the next refresh.
4. **Empty `IN` clause** (CS-G5) — see the repository plan.
