package com.demo.campaign;

import com.demo.campaign.entity.Campaign;
import com.demo.campaign.repository.CampaignRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RP-C1 and RP-C2 from docs/test_plan_repository.md — the {@code @CreatedDate} /
 * {@code @LastModifiedDate} fields on {@code BaseEntity}.
 *
 * <p>{@code @EnableJpaAuditing} lives on {@code config/MyConfig}, which {@code @DataJpaTest} does not
 * scan, and importing {@code MyConfig} directly fails because its {@code ApplicationRunner} bean
 * needs {@code CacheManager}. Hence the local {@link AuditingTestConfig} carrying nothing but the
 * annotation. {@link SchemaMigrationTest} covers the mirror case (RP-C3), where the plain slice
 * leaves {@code created} null — together they show this import is required, not incidental.
 *
 * <p>Docker must be running.
 */
@Import(TestcontainersConfiguration.class)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EntityAuditingTest {

    @TestConfiguration(proxyBeanMethods = false)
    @EnableJpaAuditing
    static class AuditingTestConfig {
    }

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private TestEntityManager entityManager;

    /** RP-C1 */
    @Test
    void save_with_auditing_enabled_then_created_and_lastModified_are_populated() {
        Campaign saved = campaignRepository.save(
                Campaign.builder().code("RP_C1").totalCapacity(1).build());
        entityManager.flush();

        assertNotNull(saved.getCreated(), "@CreatedDate should be populated by the AuditingEntityListener");
        assertNotNull(saved.getLastModified(), "@LastModifiedDate should be populated on insert too");
    }

    /** RP-C2 */
    @Test
    void update_then_lastModified_advances_past_created() throws InterruptedException {
        Campaign saved = campaignRepository.save(
                Campaign.builder().code("RP_C2").totalCapacity(1).build());
        entityManager.flush();
        var created = saved.getCreated();

        // The auditing clock has sub-millisecond resolution; pause so the two stamps cannot collide.
        Thread.sleep(20);

        saved.setTotalCapacity(2);
        campaignRepository.save(saved);
        entityManager.flush();

        assertTrue(saved.getLastModified().isAfter(created),
                () -> "lastModified " + saved.getLastModified() + " should be after created " + created);
    }
}
