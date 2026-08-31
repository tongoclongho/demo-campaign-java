package com.demo.campaign.service;

import com.demo.campaign.entity.Campaign;
import com.demo.campaign.entity.ClaimCampaign;
import com.demo.campaign.entity.ClaimCampaignStatus;
import com.demo.campaign.repository.CampaignRepository;
import com.demo.campaign.repository.ClaimCampaignRepository;
import com.demo.campaign.util.AppConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CacheManager} rebuilding derived Redis state from Postgres, against a real Redis container
 * with mocked repositories. See docs/test_plan_service.md (CM-1..CM-3); CM-4 lives in
 * {@code com.demo.campaign.CacheManagerStartupTest}.
 */
class CacheManagerTest extends AbstractRedisTest {

    private static final Long CAMPAIGN_ID = 1L;

    private CampaignRepository campaignRepository;
    private ClaimCampaignRepository claimCampaignRepository;
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        campaignRepository = mock(CampaignRepository.class);
        claimCampaignRepository = mock(ClaimCampaignRepository.class);
        cacheManager = new CacheManager(campaignRepository, claimCampaignRepository,
                new RedisServiceHelper(redisTemplate));
    }

    private static Campaign campaign(Long id, int totalCapacity) {
        var campaign = Campaign.builder().code("C" + id).totalCapacity(totalCapacity).build();
        campaign.setId(id);
        return campaign;
    }

    private static ClaimCampaign claim(Long userId, ClaimCampaignStatus status) {
        return ClaimCampaign.builder().campaignId(CAMPAIGN_ID).userId(userId).status(status).build();
    }

    private static String membersKey() {
        return AppConstants.CAMPAIGN_MEMBERS_SET + CAMPAIGN_ID;
    }

    private static String seatsKey() {
        return AppConstants.CAMPAIGN_AVAILABLE_SEATS + CAMPAIGN_ID;
    }

    /**
     * CM-1 — member set and seat counter are both recomputed from the approved rows.
     */
    @Test
    void refreshRedisCache_with_approved_claims_then_members_and_seats_rebuilt() {
        // Arrange
        when(campaignRepository.findAll()).thenReturn(List.of(campaign(CAMPAIGN_ID, 5)));
        when(claimCampaignRepository.getClaimCampaignBy(CAMPAIGN_ID, "APPROVED"))
                .thenReturn(List.of(claim(1L, ClaimCampaignStatus.APPROVED), claim(2L, ClaimCampaignStatus.APPROVED)));

        // Act
        cacheManager.refreshRedisCache();

        // Assert
        assertThat(redisTemplate.opsForSet().members(membersKey())).containsExactlyInAnyOrder("1", "2");
        assertEquals("3", redisTemplate.opsForValue().get(seatsKey()));
    }

    /**
     * CM-2 — only APPROVED rows count. The filtering happens in SQL, so what this asserts is that the
     * manager asks for the APPROVED status and consumes nothing else.
     */
    @Test
    void refreshRedisCache_with_mixed_statuses_then_only_approved_consume_seats() {
        // Arrange — the stub filters by whatever status the manager asks for
        var allClaims = List.of(
                claim(1L, ClaimCampaignStatus.APPROVED),
                claim(2L, ClaimCampaignStatus.SUBMITTED),
                claim(3L, ClaimCampaignStatus.REJECTED),
                claim(4L, ClaimCampaignStatus.APPROVED));

        when(campaignRepository.findAll()).thenReturn(List.of(campaign(CAMPAIGN_ID, 5)));
        when(claimCampaignRepository.getClaimCampaignBy(anyLong(), anyString()))
                .thenAnswer(invocation -> {
                    String status = invocation.getArgument(1);
                    return allClaims.stream().filter(c -> c.getStatus().name().equals(status)).toList();
                });

        // Act
        cacheManager.refreshRedisCache();

        // Assert
        verify(claimCampaignRepository).getClaimCampaignBy(CAMPAIGN_ID, "APPROVED");
        assertThat(redisTemplate.opsForSet().members(membersKey())).containsExactlyInAnyOrder("1", "4");
        assertEquals("3", redisTemplate.opsForValue().get(seatsKey()));
    }

    /**
     * CM-3 — a campaign with no approved claims has its member key deleted (not left stale) and gets
     * its full capacity back.
     */
    @Test
    void refreshRedisCache_with_no_claims_then_members_deleted_and_seats_at_full_capacity() {
        // Arrange — stale members left over from a previous state
        redisTemplate.opsForSet().add(membersKey(), "99");
        when(campaignRepository.findAll()).thenReturn(List.of(campaign(CAMPAIGN_ID, 5)));
        when(claimCampaignRepository.getClaimCampaignBy(CAMPAIGN_ID, "APPROVED")).thenReturn(List.of());

        // Act
        cacheManager.refreshRedisCache();

        // Assert
        assertFalse(redisTemplate.hasKey(membersKey()));
        assertEquals("5", redisTemplate.opsForValue().get(seatsKey()));
    }

    /**
     * CM-3 companion — the seat refresh takes {@code max(current, recomputed)}, so a decrement that is
     * still in flight is not clobbered back up to the recomputed value.
     */
    @Test
    void refreshRedisCache_with_higher_current_seats_then_current_value_kept() {
        // Arrange — Redis says 5, Postgres-derived value is 3
        redisTemplate.opsForValue().set(seatsKey(), "5");
        when(campaignRepository.findAll()).thenReturn(List.of(campaign(CAMPAIGN_ID, 5)));
        when(claimCampaignRepository.getClaimCampaignBy(CAMPAIGN_ID, "APPROVED"))
                .thenReturn(List.of(claim(1L, ClaimCampaignStatus.APPROVED), claim(2L, ClaimCampaignStatus.APPROVED)));

        // Act
        cacheManager.refreshRedisCache();

        // Assert
        assertEquals("5", redisTemplate.opsForValue().get(seatsKey()));
    }

    /**
     * Every campaign is reconciled, not just the first.
     */
    @Test
    void refreshRedisCache_with_multiple_campaigns_then_each_one_refreshed() {
        // Arrange
        when(campaignRepository.findAll()).thenReturn(List.of(campaign(1L, 5), campaign(2L, 3)));
        when(claimCampaignRepository.getClaimCampaignBy(anyLong(), anyString())).thenReturn(List.of());

        // Act
        cacheManager.refreshRedisCache();

        // Assert
        assertEquals("5", redisTemplate.opsForValue().get(AppConstants.CAMPAIGN_AVAILABLE_SEATS + 1L));
        assertEquals("3", redisTemplate.opsForValue().get(AppConstants.CAMPAIGN_AVAILABLE_SEATS + 2L));
        Mockito.verify(claimCampaignRepository).getClaimCampaignBy(1L, "APPROVED");
        Mockito.verify(claimCampaignRepository).getClaimCampaignBy(2L, "APPROVED");
    }
}
