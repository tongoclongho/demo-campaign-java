# Demo Campaign 

## Statement

Design and implement a Spring Boot service that allows users to claim a unique voucher code from a limited pool.

1. Voucher Campaign Creation: An admin endpoint to create a campaign (e.g., BLACK_FRIDAY) with a specific total capacity (e.g., 5,000 vouchers).
2. Voucher Claiming: A public endpoint POST /api/v1/claims.
   ○ Input: userId, campaignId.
   ○ Constraints: One user can only claim one voucher per campaign. No "overselling" (if capacity is 5,000, the 5,001st person must fail).

3. The Priority Logic (Algorithm): Each user has a loyaltyPoints attribute (mocked).
    If requests arrive simultaneously, the system should prioritize users with higher loyalty points.
- Example: 
  - When: 
     - Request 1st comes at 2026-09-01 10:00:00 with user point: 100
     - Request 2nd comes at 2026-09-01 10:00:00 with user point: 101
     - There is only one available slot for the requested campaign
  - Then: 
     - 1st -> Rejected
     - 2nd -> Approved

## Technology Stack

- **Spring Boot**: 4.0.8
- **Spring Boot Starter Data JPA**: 
- **Spring Boot Starter Webmvc**: 
- **Spring Boot Starter Redis**: 
- **Springdoc Openapi Starter Webmvc-UI**: 3.1.0
- **Java Version**: 17
- **Database**: Postgres, Redis
- **Database Migration Tool**: Flyway
- **API Documentation**: Swagger
- **Build Tool**: Maven wrapper
- **Code Generator Tool**: Lombok, MapStruct
- **Testing**: JUnit 5, Spring Boot Test, Testcontainer

### Out of scope: 
- **Spring Boot Starter Security** is not used to reduce complexity

## Run locally

### Runtime dependencies

```bash
docker run --name my_postgres -e POSTGRES_USER=user -e POSTGRES_PASSWORD=password -e POSTGRES_DB=db -p 5432:5432 -d postgres
docker run --name my-redis -p 6379:6379 -d redis
```

**Tests require a running Docker daemon** — every non-mock test starts Testcontainers.

### Commands

```bash
./mvnw clean package -DskipTests            # build + skip tests
./mvnw compile                  # compile (runs Lombok then MapStruct annotation processors)
./mvnw test                     # all tests
./mvnw test -Dtest=ClaimCampaignServiceImplTest              # one test class

./mvnw spring-boot:run          # run the app on :8080
```

## Steps to verify

### 1. Open Swagger UI: `http://localhost:8080/swagger-ui/index.html`.
### 2. Try to get list available campaigns via `GET /api/v1/campaign`
### 3. Try to submit a claim request for a test user via `POST /api/v1/claim`
### 4. Poll the status of claim request via `GET /api/v1/claim?userId=&campaignId=`

## Technical explanation: 
The core idea of this project is that use Redis sorted set to pool arriving requests (key:value = reverseTimestamp:userLoyaltyPoint_userId) 
to ensure the main business requirement:
- First comes then first served
- Comes the same time then higher loyalty point served

## Functional requirements

### 1. Application on startup
- Flyway migration if any
- AppRunner to init redis

### 2. Scheduling Jobs
- The `CacheManager.refreshRedisCache` runs every 5s to refresh Redis
- The ```ClaimCampaignServiceImpl.executeClaimCampaignSubmission``` runs every 1s to check claim submissions

### 3. `GET /api/v1/campaign` - Admin gets list of campaigns

### 4. `POST /api/v1/campaign` - Admin creates a campaign

### 5. `POST /api/v1/claim` - User requests a claim campaign

### 6. `GET /api/v1/claim?userId=&campaignId=` - User polls the claim campaign status


## Reference
- [Entity relationship diagram](docs/entity_model.md)
- [Sequence diagram](docs/sequence_diagram.md)




