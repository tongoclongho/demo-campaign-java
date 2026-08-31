package com.demo.campaign;

import com.demo.campaign.entity.Campaign;
import com.demo.campaign.repository.CampaignRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RP-C3 and RP-C6 from docs/test_plan_repository.md.
 *
 * <p>Deliberately <em>without</em> the local {@code @EnableJpaAuditing} configuration that
 * {@link EntityAuditingTest} adds — RP-C3 documents that the plain slice leaves {@code created}
 * null, which is what makes that import load-bearing rather than decorative.
 *
 * <p>Docker must be running.
 */
@Import(TestcontainersConfiguration.class)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SchemaMigrationTest {

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private TestEntityManager entityManager;

    /** RP-C3 — no {@code @EnableJpaAuditing} in this slice, so nothing fills the audit columns. */
    @Test
    void save_without_auditing_config_then_created_stays_null() {
        Campaign saved = campaignRepository.save(
                Campaign.builder().code("RP_C3").totalCapacity(1).build());
        entityManager.flush();
        entityManager.clear();

        Campaign reloaded = campaignRepository.findById(saved.getId()).orElseThrow();
        assertNull(reloaded.getCreated(),
                "the AuditingEntityListener is inert without @EnableJpaAuditing — see EntityAuditingTest");
        assertNull(reloaded.getLastModified());
    }

    /** RP-C6 — smoke test that both Flyway migrations ran. */
    @Test
    void flyway_migrations_created_the_tables_and_seed_data() {
        assertTrue(tableExists("campaign"), "V1__create_tables.sql should create campaign");
        assertTrue(tableExists("campaign_user"), "V1__create_tables.sql should create campaign_user");
        assertTrue(tableExists("claim_campaign"), "V1__create_tables.sql should create claim_campaign");

        assertEquals(7L, countOf("campaign_user"), "V2__create_test_data.sql seeds 7 users");

        @SuppressWarnings("unchecked")
        List<Object[]> campaigns = entityManager.getEntityManager()
                .createNativeQuery("SELECT code, total_capacity FROM campaign ORDER BY code")
                .getResultList();
        assertEquals(2, campaigns.size(), "V2__create_test_data.sql seeds 2 campaigns");
        assertEquals("BLACK_FRIDAY", campaigns.get(0)[0]);
        assertEquals(5, ((Number) campaigns.get(0)[1]).intValue());
        assertEquals("HAPPY_FRIDAY", campaigns.get(1)[0]);
        assertEquals(3, ((Number) campaigns.get(1)[1]).intValue());
    }

    private boolean tableExists(String table) {
        Object count = entityManager.getEntityManager()
                .createNativeQuery("SELECT COUNT(1) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = :table")
                .setParameter("table", table)
                .getSingleResult();
        return ((Number) count).intValue() == 1;
    }

    private long countOf(String table) {
        Object count = entityManager.getEntityManager()
                .createNativeQuery("SELECT COUNT(1) FROM " + table)
                .getSingleResult();
        return ((Number) count).longValue();
    }
}
