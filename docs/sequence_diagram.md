## 1. Application on startup

### 1. Flyway applies `db/migration/V1__create_tables.sql` and `V2__create_test_data.sql`.
### 2. The `com.demo.campaign.config.MyConfig.startupRunner` fulfills Redis from Postgres.

```mermaid
sequenceDiagram
    autonumber
    participant BOOT as Spring Boot
    participant FW as Flyway
    participant RUN as MyConfig.startupRunner
    participant PG as Postgres
    participant RD as Redis

    BOOT->>FW: migrate()
    FW->>PG: apply V1 schema, V2 seed data
    BOOT->>RUN: run(args)
    RUN->>PG: loads data from Postgres
    RUN->>RD: populates data to Redis
    RUN->>BOOT: Application is ready to serve requests
```

## 2. Scheduling Jobs

### 1. The `com.demo.campaign.service.CacheManager.refreshRedisCache` runs every 60 seconds to refresh Redis

```mermaid
sequenceDiagram
    autonumber
    participant BOOT as Spring Scheduler
    participant CM as CacheManager
    participant CR as CampaignRepository
    participant CCR as ClaimCampaignRepository
    participant RSH as RedisServiceHelper
    participant PG as Postgres
    participant RD as Redis

   
    BOOT->>CM: refreshRedisCache()

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

    CM-->>BOOT: done
```

### 2. The claim check job ```com.demo.campaign.service.ClaimCampaignServiceImpl.executeClaimCampaignSubmission``` 
- Runs every 5 seconds
- Turns `SUBMITTED` claims into `APPROVED` or `REJECTED` following in order: claim date -> loyalty points -> user id

```mermaid
sequenceDiagram
    autonumber
    participant SCH as Spring Scheduler
    participant CCS as ClaimCampaignServiceImpl
    participant RSH as RedisServiceHelper
    participant CCR as ClaimCampaignRepository
    participant RD as Redis
    participant PG as Postgres

    SCH->>CCS: executeClaimCampaignSubmission()
    CCS->>RSH: getCampaignIdsForClaimCheck()
    RSH->>RD: SCARD claim_campaign_priority_ids_set

    alt no campaign ids registered
        RSH-->>CCS: empty list
    else ids registered
        RSH->>RD: SPOP claim_campaign_priority_ids_set count
        RSH-->>CCS: campaignIds

        loop for each campaignId - processClaimCheck
            loop while ZCARD claim_campaign_priority_set#{campaignId} > 0
                CCS->>RD: ZPOPMAX claim_campaign_priority_set#35;campaignId min(zcard, batchSize)
                RD-->>CCS: tuples of "loyaltyPoints#35;userId"

                loop for each tuple - processClaim
                    CCS->>CCR: findByUserIdAndCampaignId(userId, campaignId)
                    CCR->>PG: SELECT * FROM claim_campaign WHERE user_id = ? AND campaign_id = ?
                    CCR-->>CCS: entity not found or not SUBMITTED
                    CCS->>CCS: log and return
                    

                    CCS->>RSH: reserveCampaignSeat(campaignId)
                    RSH->>RD: DECR campaign_available_seats#35;campaignId
                    
                    alt result < 0
                        RSH-->>CCS: CampaignNoAvailableSeatsException
                        CCS->>CCS: status REJECTED, reason = message
                    else seat reserved
                        CCS->>RSH: addUserToCampaignMembers(campaignId, userId)
                        RSH->>RD: SADD campaign_members_set#35;campaignId userId
                    
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


## 3. `POST /api/v1/claim` - User requests a claim campaign

- Persists the claim as `SUBMITTED`, and enqueues it to Redis sorted set. The response is an acknowledgement, **not** a decision — approval happens later in the scheduled job.

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant CC as ClaimController
    participant CCS as ClaimCampaignServiceImpl
    participant RSH as RedisServiceHelper
    participant RD as Redis
    participant CCR as ClaimCampaignRepository
    participant CR as CampaignRepository
    participant CUR as CampaignUserRepository
    participant MAP as MapperConfig
    participant PG as Postgres
    participant ADV as ControllerAdvice

    U->>CC: POST /api/v1/claim {campaignId, userId}
    Note over CC: claimDate is @JsonIgnore —<br/>always defaulted to LocalDateTime.now()

    alt @Valid fails (campaignId or userId null)
        CC-->>ADV: MethodArgumentNotValidException
        ADV-->>U: 400 {field: message}
    else payload valid
        CC->>CCS: claimCampaign(request)

    %% ---- guard 1: already a member ----
        CCS->>RSH: requireUserNotInCampaignMembers(userId, campaignId)
        RSH->>RD: SISMEMBER campaign_members_set#35;{campaignId} userId
        RD-->>RSH: true / false
        alt user already in members set
            RSH-->>ADV: CampaignClaimedAlreadyException
            ADV-->>U: 400 "The user has claimed the campaign already."
        else not a member
        %% ---- guard 2: seats available ----
            CCS->>RSH: requireCampaignAvailableSeats(campaignId)
            RSH->>RD: GET campaign_available_seats#35;{campaignId}
            RD-->>RSH: remainingSeats (or null)
            alt remainingSeats null or <= 0
                RSH-->>ADV: CampaignNoAvailableSeatsException
                ADV-->>U: 400 "No available seats for the campaign id ..."
            else seats available
            %% ---- idempotent short-circuit ----
                CCS->>CCR: findByUserIdAndCampaignId(userId, campaignId)
                CCR->>PG: SELECT * FROM claim_campaign<br/>WHERE user_id = ? AND campaign_id = ?
                PG-->>CCR: row / empty
                alt claim already exists
                    CCS->>MAP: toDTO(existing)
                    MAP-->>CCS: ClaimCampaignDTO
                    CCS-->>CC: existing claim (current status)
                    CC-->>U: 200 ClaimCampaignDTO
                    Note over CCS,RD: early return — no INSERT, no ZADD
                else no claim yet
                %% ---- referential checks ----
                    CCS->>CR: findById(campaignId)
                    CR->>PG: SELECT * FROM campaign WHERE id = ?
                    alt campaign missing
                        CCS-->>ADV: NoSuchCampaignException
                        ADV-->>U: 400 "Campaign not found."
                    else campaign found
                        CCS->>CUR: findById(userId)
                        CUR->>PG: SELECT * FROM campaign_user WHERE id = ?
                        alt user missing
                            CCS-->>ADV: NoSuchUserException
                            ADV-->>U: 400 "User not found"
                        else user found
                            CUR-->>CCS: user (loyaltyPoints)

                        %% ---- persist ----
                            CCS->>CCR: save(ClaimCampaign{status: SUBMITTED})
                            CCR->>PG: INSERT INTO claim_campaign
                            PG-->>CCR: generated id
                            CCR-->>CCS: saved entity

                        %% ---- enqueue ----
                            CCS->>RSH: sendClaimToPriorityQueue(ClaimSubmissionDTO)
                            RSH->>RD: ZADD claim_campaign_priority_set#35;{campaignId} score member
                            Note over RSH,RD: score = Long.MAX_VALUE - claimDate.toEpochMilli()<br/>(reversed, so ZPOPMAX yields earliest claim first)<br/>member = "loyaltyPoints#35;userId"
                            RSH->>RD: SADD claim_campaign_priority_ids_set campaignId
                            Note over RSH,RD: registers the campaign for the next 5s job sweep

                            CCS->>MAP: toDTO(saved)
                            MAP-->>CCS: ClaimCampaignDTO
                            CCS-->>CC: ClaimCampaignDTO
                            CC-->>U: 200 status = SUBMITTED
                        end
                    end
                end
            end
        end
    end

    opt any unhandled exception
        CCS-->>ADV: Exception
        ADV-->>U: 500 "An unexpected error occurred on the server."
    end
```

## 4. `GET /api/v1/claim?userId=&campaignId=` - User polls the claim campaign status

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

## 5. `GET /api/v1/campaign` - Admin gets list of campaigns

- Read-only, Postgres only — no Redis is involved. `totalCapacity` comes from the `campaign` row, while `usedSeats` is aggregated on the fly by counting `APPROVED` claims.
- A campaign with no `APPROVED` claim gets no row from the aggregate query, so its `usedSeats` stays at the default `0`.

```mermaid
sequenceDiagram
    autonumber
    actor A as Admin
    participant CC as CampaignController
    participant CS as CampaignServiceImpl
    participant CR as CampaignRepository
    participant CCR as ClaimCampaignRepository
    participant MAP as MapperConfig
    participant ADV as ControllerAdvice
    participant PG as Postgres

    A->>CC: GET /api/v1/campaign
    CC->>CS: getCampaigns()

    CS->>CR: findAll()
    CR->>PG: SELECT * FROM campaign
    PG-->>CR: campaign rows
    CR-->>CS: campaigns

    loop for each campaign
        CS->>MAP: toDTO(campaign)
        Note over CS,MAP: id to campaignId, code to campaignCode<br/>usedSeats defaults to 0
        MAP-->>CS: CampaignDTO
        CS->>CS: map.put(campaign.getId(), dto)
    end

    CS->>CCR: aggregateCampaignUsedSeats(map.keySet())
    CCR->>PG: SELECT campaign_id, COUNT(*) FROM claim_campaign<br/>WHERE campaign_id IN :ids AND status='APPROVED'<br/>GROUP BY campaign_id
    PG-->>CCR: rows of campaignId and usedSeats
    CCR-->>CS: List of CampaignUsedSeats

    loop for each row
        alt dto exists and usedSeats not null
            CS->>CS: dto.setUsedSeats(usedSeats)
        else no matching dto or null count
            CS->>CS: skip - usedSeats stays 0
        end
    end

    CS-->>CC: Collection of CampaignDTO - map.values()
    CC-->>A: 200 list of campaigns

    opt unexpected error
        CS-->>ADV: Exception
        ADV-->>A: 500 ErrorResponse
    end
```

## 6. `POST /api/v1/campaign` - Admin creates a campaign

- The campaign code is trimmed and upper-cased before the uniqueness check, so `" summer "` and `"SUMMER"` collide.
- Nothing is written to Redis here. The `campaign_available_seats#{campaignId}` key is only created by the next `CacheManager.refreshRedisCache` run (up to 60 seconds later)

```mermaid
sequenceDiagram
    autonumber
    actor A as Admin
    participant CC as CampaignController
    participant CS as CampaignServiceImpl
    participant CR as CampaignRepository
    participant MAP as MapperConfig
    participant ADV as ControllerAdvice
    participant PG as Postgres

    A->>CC: POST /api/v1/campaign {campaignCode, totalCapacity}
    CC->>CC: @Valid - campaignCode not blank, totalCapacity not null and >= 1

    alt bean validation fails
        CC-->>ADV: MethodArgumentNotValidException
        ADV-->>A: 400 map of field to message
    else valid payload
        CC->>CS: createCampaign(request)
        CS->>CS: codeUpperCase = campaignCode.trim().toUpperCase()
        CS->>CS: build Campaign {code, totalCapacity}

        CS->>CR: existingCampaignCode(codeUpperCase)
        CR->>PG: SELECT COUNT(1) FROM campaign WHERE code = ?
        PG-->>CR: count
        CR-->>CS: count

        alt count > 0
            CS-->>ADV: ExistingCampaignCodeException
            ADV-->>A: 400 ErrorResponse "Existing campaign code"
        else code is free
            CS->>CR: save(campaign)
            CR->>PG: INSERT INTO campaign
            Note over CR,PG: created and last_modified filled by JPA auditing
            PG-->>CR: generated id
            CR-->>CS: saved campaign
            CS->>MAP: toDTO(saved)
            MAP-->>CS: CampaignDTO - usedSeats 0
            CS-->>CC: CampaignDTO
            CC-->>A: 201 CREATED
        end
    end
```
