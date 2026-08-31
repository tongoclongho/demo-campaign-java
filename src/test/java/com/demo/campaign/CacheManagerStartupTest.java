package com.demo.campaign;

import com.demo.campaign.entity.Campaign;
import com.demo.campaign.repository.CampaignRepository;
import com.demo.campaign.util.AppConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * CM-4 — the {@code ApplicationRunner} in {@code config/MyConfig} warms the Redis cache at startup, so
 * a freshly booted context already has a seat counter for every Flyway-seeded campaign.
 * See docs/test_plan_service.md.
 *
 * <p>Full context with real Postgres and Redis containers, so Docker must be running.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CacheManagerStartupTest {

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void startupRunner_then_available_seats_cached_for_every_seeded_campaign() {
        // Arrange — V2__create_test_data.sql seeds the campaigns
        List<Campaign> campaigns = new ArrayList<>();
        campaignRepository.findAll().forEach(campaigns::add);
        assertFalse(campaigns.isEmpty(), "expected the Flyway seed data to create campaigns");

        // Assert — the startup runner has already populated the counters
        for (var campaign : campaigns) {
            String seatsKey = AppConstants.CAMPAIGN_AVAILABLE_SEATS + campaign.getId();
            String cached = redisTemplate.opsForValue().get(seatsKey);

            assertNotNull(cached, () -> "no cached seats for campaign " + campaign.getCode());
            assertEquals(campaign.getTotalCapacity().intValue(), Integer.parseInt(cached),
                    "campaign " + campaign.getCode() + " has no approved claims, so all seats are free");
        }
    }
}
