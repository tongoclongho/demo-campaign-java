# Application on startup

Flyway applies `db/migration/V1__create_tables.sql` and `V2__create_test_data.sql`, then the
`startupRunner` `ApplicationRunner` bean in `MyConfig` warms Redis from Postgres. The same
`refreshRedisCache` method also runs every 60 seconds on a schedule.

```mermaid
sequenceDiagram
    autonumber
    participant BOOT as Spring Boot
    participant FW as Flyway
    participant RUN as MyConfig.startupRunner
    participant CM as CacheManager
    participant CR as CampaignRepository
    participant CCR as ClaimCampaignRepository
    participant RSH as RedisServiceHelper
    participant PG as Postgres
    participant RD as Redis

    BOOT->>FW: migrate()
    FW->>PG: apply V1 schema, V2 seed data
    BOOT->>RUN: run(args)
    RUN->>CM: refreshRedisCache()

    loop for each campaign in campaignRepository.findAll()
        CM->>CR: findAll()
        CR->>PG: SELECT * FROM campaign
        CM->>CCR: getClaimCampaignBy(campaignId, "APPROVED")
        CCR->>PG: SELECT * FROM claim_campaign WHERE campaign_id = ? AND status = 'APPROVED'
        PG-->>CM: approved claims
        CM->>RSH: refreshCampaignMembersCache(campaignId, userIds)

        alt userIds is empty
            RSH->>RD: DEL campaign_members_set#35;campaignId
        else userIds present
            RSH->>RD: SADD campaign_members_set#35;campaignId userIds
        end

        CM->>RSH: refreshCampaignAvailableSeatsCache(campaignId, totalCapacity - approvedCount)
        RSH->>RD: GET campaign_available_seats#35;campaignId
        RD-->>RSH: currentSeats (or null to 0)
        RSH->>RD: SET campaign_available_seats#35;campaignId max(currentSeats, availableSeats)
    end

    CM-->>RUN: done
```

`refreshCampaignAvailableSeatsCache` writes `max(current, recomputed)` instead of overwriting, so a
refresh that races with in-flight `DECR`s cannot silently hand out seats twice.

# User submits a claim campaign

`POST /api/v1/claim` performs cheap Redis guards, persists the claim as `SUBMITTED`, and enqueues it.
The response is an acknowledgement, **not** a decision — approval happens later in the scheduled job.

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant CC as ClaimController
    participant CCS as ClaimCampaignServiceImpl
    participant RSH as RedisServiceHelper
    participant CR as CampaignRepository
    participant CUR as CampaignUserRepository
    participant CCR as ClaimCampaignRepository
    participant MAP as MapperConfig
    participant ADV as ControllerAdvice
    participant RD as Redis
    participant PG as Postgres

    U->>CC: POST /api/v1/claim {campaignId, userId}
    CC->>CC: @Valid - campaignId and userId not null
    CC->>CCS: claimCampaign(request)

    CCS->>RSH: requireUserNotInCampaignMembers(userId, campaignId)
    RSH->>RD: SISMEMBER campaign_members_set#35;campaignId userId

    alt already a member
        RSH-->>ADV: CampaignClaimedAlreadyException
        ADV-->>U: 400 ErrorResponse
    else not a member
        CCS->>RSH: requireCampaignAvailableSeats(campaignId)
        RSH->>RD: GET campaign_available_seats#35;campaignId

        alt null or <= 0
            RSH-->>ADV: CampaignNoAvailableSeatsException
            ADV-->>U: 400 ErrorResponse
        else seats remain
            CCS->>CR: findById(campaignId)
            CR->>PG: SELECT * FROM campaign WHERE id = ?

            alt campaign missing
                CCS-->>ADV: NoSuchCampaignException
                ADV-->>U: 400 ErrorResponse
            else campaign found
                CCS->>CUR: findById(userId)
                CUR->>PG: SELECT * FROM campaign_user WHERE id = ?

                alt user missing
                    CCS-->>ADV: NoSuchUserException
                    ADV-->>U: 400 ErrorResponse
                else user found
                    CCS->>CCR: save(ClaimCampaign status SUBMITTED)
                    CCR->>PG: INSERT INTO claim_campaign
                    CCS->>RSH: sendClaimToPriorityQueue(claimSubmission)
                    RSH->>RD: ZADD claim_campaign_priority_set#35;campaignId score value
                    Note over RSH,RD: score = Long.MAX_VALUE - claimDate epochMilli<br/>value = "loyaltyPoints#35;userId"
                    RSH->>RD: SADD claim_campaign_priority_ids_set campaignId
                    CCS->>MAP: toDTO(saved)
                    CCS-->>CC: ClaimCampaignDTO
                    CC-->>U: 200 status SUBMITTED
                end
            end
        end
    end
```

The guards at the start are fail-fast only; they read Redis without reserving anything. The binding
decision is made later, under `DECR`/`SADD`, so a claim that passes these checks can still be
rejected.

# Scheduled claim settlement

`ClaimCampaignServiceImpl.executeClaimCampaignSubmission` runs every 5 seconds
(`@Scheduled(fixedRate = 5000)`) and turns `SUBMITTED` claims into `APPROVED` or `REJECTED`.
Claims are drained with `popMax` against an inverted-timestamp score, so the **earliest** claim is
settled first. Tuple of loyalty_points#user_id in value to ensure if requests arrive simultaneously, 
the system should prioritize users with higher loyalty points.

```mermaid
sequenceDiagram
    autonumber
    participant SCH as Spring Scheduler
    participant CCS as ClaimCampaignServiceImpl
    participant RSH as RedisServiceHelper
    participant CCR as ClaimCampaignRepository
    participant RD as Redis
    participant PG as Postgres

    SCH->>CCS: executeClaimCampaignSubmission() every 5s
    CCS->>RSH: getCampaignIdsForClaimCheck()
    RSH->>RD: SCARD claim_campaign_priority_ids_set

    alt no campaign ids registered
        RSH-->>CCS: empty list
    else ids registered
        RSH->>RD: SPOP claim_campaign_priority_ids_set count
        RSH-->>CCS: campaignIds

        loop for each campaignId - processClaimCheck
            loop while ZCARD claim_campaign_priority_set#campaignId > 0
                CCS->>RD: ZPOPMAX claim_campaign_priority_set#35;campaignId min(zcard, batchSize)
                RD-->>CCS: tuples of "loyaltyPoints#35;userId"

                loop for each tuple - processClaim
                    CCS->>CCR: findByUserIdAndCampaignId(userId, campaignId)
                    CCR->>PG: SELECT * FROM claim_campaign WHERE user_id = ? AND campaign_id = ?

                    CCS->>RSH: reserveCampaignSeat(campaignId)
                    RSH->>RD: DECR campaign_available_seats#id

                    alt result < 0
                        RSH-->>CCS: CampaignNoAvailableSeatsException
                        CCS->>CCS: status REJECTED, reason = message
                    else seat reserved
                        CCS->>RSH: addUserToCampaignMembers(campaignId, userId)
                        RSH->>RD: SADD campaign_members_set#id userId

                        alt SADD added 0 - already a member
                            RSH-->>CCS: CampaignClaimedAlreadyException
                            CCS->>CCS: status REJECTED, reason = message
                        else newly added
                            CCS->>CCS: status APPROVED
                        end
                    end

                    CCS->>CCR: save(claimCampaign)
                    CCR->>PG: UPDATE claim_campaign SET status, reason
                end
            end
        end
    end

    CCS->>CCS: log processed count and elapsed time
```

Batch size comes from `redis.zset.batch-size` (default 100). A rejected claim does **not** give its
`DECR`-ed seat back; the counter is corrected by the next `refreshRedisCache` run, which recomputes
available seats from `APPROVED` rows in Postgres.

# Client polls the claim status

Because the decision is asynchronous, the caller polls. This endpoint reads the repository directly
rather than going through `ClaimCampaignService`.

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant CC as ClaimController
    participant CCR as ClaimCampaignRepository
    participant MAP as MapperConfig
    participant ADV as ControllerAdvice
    participant PG as Postgres

    U->>CC: GET /api/v1/claim?userId=&campaignId=
    CC->>CCR: findByUserIdAndCampaignId(userId, campaignId)
    CCR->>PG: SELECT * FROM claim_campaign WHERE user_id = ? AND campaign_id = ?

    alt no row
        CC-->>ADV: NoSuchClaimCampaignException
        ADV-->>U: 400 ErrorResponse
    else row found
        CC->>MAP: toDTO(claimCampaign)
        MAP-->>CC: ClaimCampaignDTO
        CC-->>U: 200 status SUBMITTED APPROVED or REJECTED
    end
```
