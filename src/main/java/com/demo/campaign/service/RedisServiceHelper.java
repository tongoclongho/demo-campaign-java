package com.demo.campaign.service;


import com.demo.campaign.dto.ClaimSubmissionDTO;
import com.demo.campaign.service.exception.CampaignClaimedAlreadyException;
import com.demo.campaign.service.exception.CampaignNoAvailableSeatsException;
import com.demo.campaign.util.AppConstants;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class RedisServiceHelper {

    private final StringRedisTemplate redisTemplate;

    public RedisServiceHelper(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void requireUserNotInCampaignMembers(@NotNull Long userId, @NotNull Long campaignId) {
        var campaignMembersSetKey = AppConstants.CAMPAIGN_MEMBERS_SET + campaignId;
        boolean contains = redisTemplate.opsForSet().isMember(campaignMembersSetKey, userId.toString());
        if (contains)
            throw new CampaignClaimedAlreadyException("The user has claimed the campaign already.");
    }

    public void requireCampaignAvailableSeats(@NotNull Long campaignId) {
        var campaignMembersSetKey = AppConstants.CAMPAIGN_AVAILABLE_SEATS + campaignId;
        String remainingSeats = redisTemplate.opsForValue().get(campaignMembersSetKey);

        if(remainingSeats == null || Integer.parseInt(remainingSeats) <= 0)
            throw new CampaignNoAvailableSeatsException("No available seats for the campaign id " + campaignId);
    }


    public void sendClaimToPriorityQueue(ClaimSubmissionDTO claim) {
        var redisKey = AppConstants.CLAIM_CAMPAIGN_PRIORITY_ZSET + claim.getCampaignId();
        var redisValue = serializeRedisValue(claim.getUserId(), claim.getUserPoints());
        var reveredTimestamp = getReveredTimestampAsScore(claim.getClaimDate());

        log.info("Submit claim to ZSET with details: campaign_id {} - claim_date {} - loyalty_points#user_id {}", claim.getCampaignId(), claim.getClaimDate(), redisValue);
        redisTemplate.opsForZSet().add(redisKey, redisValue, reveredTimestamp);

        // register campaign_id for the next claim check job
        redisTemplate.opsForSet().add(AppConstants.CLAIM_CAMPAIGN_PRIORITY_IDS_SET, claim.getCampaignId().toString());
    }

    public List<Long> getCampaignIdsForClaimCheck() {
        Long numberCampaignIds = redisTemplate.opsForSet().size(AppConstants.CLAIM_CAMPAIGN_PRIORITY_IDS_SET);
        if (numberCampaignIds > 0)
            return redisTemplate.opsForSet()
                    .pop(AppConstants.CLAIM_CAMPAIGN_PRIORITY_IDS_SET, numberCampaignIds)
                    .stream().map(Long::parseLong).toList();


        return Collections.emptyList();
    }

    private String serializeRedisValue(Long userId, Integer userPoints) {
        return userPoints + "#" + userId;
    }


    private Long getReveredTimestampAsScore(LocalDateTime dateTime) {
        return Long.MAX_VALUE - dateTime.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    public Optional<Boolean> addUserToCampaignMembers(Long campaignId, Long userId) {
        var campaignMembersSetKey = AppConstants.CAMPAIGN_MEMBERS_SET + campaignId;
        long result = redisTemplate.opsForSet().add(campaignMembersSetKey, userId.toString());

        return result > 0 ? Optional.of(Boolean.TRUE) : Optional.empty();
    }

    public Optional<Long> reserveCampaignSeat(Long campaignId) {
        var campaignAvailableSeatKey = AppConstants.CAMPAIGN_AVAILABLE_SEATS + campaignId;
        long currentSeat = redisTemplate.opsForValue().decrement(campaignAvailableSeatKey, 1);

        return currentSeat >= 0 ? Optional.of(currentSeat) : Optional.empty();
    }

    public void refreshCampaignMembersCache(Long campaignId, Collection<Long> userIds) {
        try {
            long start = System.currentTimeMillis();
            var campaignMembersSetKey = AppConstants.CAMPAIGN_MEMBERS_SET + campaignId;

            if (userIds.isEmpty()) {
                redisTemplate.delete(campaignMembersSetKey);
            } else {
                String[] arrays = userIds.stream().map(Object::toString).toArray(String[]::new);
                redisTemplate.opsForSet().add(campaignMembersSetKey, arrays);
            }

            log.info("Refreshed CAMPAIGN_MEMBERS_SET campaign id {} took {} seconds", campaignId, (System.currentTimeMillis() - start) / 1000.0);

        } catch (Exception ex) {
            log.error("Error when refreshing CAMPAIGN_MEMBERS_SET campaign id " + campaignId, ex);
        }
    }

    public void refreshCampaignAvailableSeatsCache(Long campaignId, int availableSeats) {
        try {
            long start = System.currentTimeMillis();
            var campaignAvailableSeatsKey = AppConstants.CAMPAIGN_AVAILABLE_SEATS + campaignId;

            String value = redisTemplate.opsForValue().get(campaignAvailableSeatsKey);

            int currentSeats = value != null ? Integer.parseInt(value) : 0;
            redisTemplate.opsForValue().set(campaignAvailableSeatsKey, String.valueOf(Math.max(currentSeats, availableSeats)));

            log.info("Refreshed CAMPAIGN_AVAILABLE_SEATS campaign id {} took {} seconds", campaignId, (System.currentTimeMillis() - start) / 1000.0);

        } catch (Exception ex) {
            log.error("Error when refreshing CAMPAIGN_AVAILABLE_SEATS campaign id " + campaignId, ex);
        }

    }



}
