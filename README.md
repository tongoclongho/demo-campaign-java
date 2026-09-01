# Demo Campaign 

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

**Swagger UI**: `http://localhost:8080/swagger-ui/index.html`.

## Functional requirements

### 1. Application on startup

### 2. Scheduling Jobs
- The `CacheManager.refreshRedisCache` runs every 60s to refresh Redis
- The ```ClaimCampaignServiceImpl.executeClaimCampaignSubmission``` runs every 5s to check claim submissions

### 3. `GET /api/v1/campaign` - Admin gets list of campaigns

### 4. `POST /api/v1/campaign` - Admin creates a campaign

### 5. `POST /api/v1/claim` - User requests a claim campaign

### 6. `GET /api/v1/claim?userId=&campaignId=` - User polls the claim campaign status


## Reference
- [Entity relationship diagram](docs/entity_model.md)
- [Sequence diagram](docs/sequence_diagram.md)




