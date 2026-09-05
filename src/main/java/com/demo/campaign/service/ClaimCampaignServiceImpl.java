package com.demo.campaign.service;

import com.demo.campaign.dto.ClaimCampaignDTO;
import com.demo.campaign.dto.ClaimSubmissionDTO;
import com.demo.campaign.dto.CreateClaimCampaign;
import com.demo.campaign.entity.ClaimCampaign;
import com.demo.campaign.entity.ClaimCampaignStatus;
import com.demo.campaign.repository.CampaignRepository;
import com.demo.campaign.repository.CampaignUserRepository;
import com.demo.campaign.repository.ClaimCampaignRepository;
import com.demo.campaign.service.exception.CampaignClaimedAlreadyException;
import com.demo.campaign.service.exception.CampaignNoAvailableSeatsException;
import com.demo.campaign.service.exception.NoSuchCampaignException;
import com.demo.campaign.service.exception.NoSuchUserException;
import com.demo.campaign.service.mapper.MapperConfig;
import com.demo.campaign.util.AppConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class ClaimCampaignServiceImpl implements ClaimCampaignService {

    private final StringRedisTemplate redisTemplate;

    private final ClaimCampaignRepository claimCampaignRepository;

    private final CampaignRepository campaignRepository;

    private final CampaignUserRepository campaignUserRepository;

    private final MapperConfig mapper;

    @Value("${redis.zset.batch-size:100}")
    private Long batchSize;

    private final RedisServiceHelper redisServiceHelper;

    public ClaimCampaignServiceImpl(StringRedisTemplate redisTemplate,
                                    ClaimCampaignRepository claimCampaignRepository,
                                    CampaignRepository campaignRepository,
                                    CampaignUserRepository campaignUserRepository,
                                    MapperConfig mapper, RedisServiceHelper redisServiceHelper) {
        this.redisTemplate = redisTemplate;
        this.claimCampaignRepository = claimCampaignRepository;
        this.campaignRepository = campaignRepository;
        this.campaignUserRepository = campaignUserRepository;
        this.mapper = mapper;
        this.redisServiceHelper = redisServiceHelper;
    }

    @Override
    public ClaimCampaignDTO claimCampaign(CreateClaimCampaign request) {
        redisServiceHelper.requireUserNotInCampaignMembers(request.getUserId(), request.getCampaignId());
        redisServiceHelper.requireCampaignAvailableSeats(request.getCampaignId());

        Optional<ClaimCampaign> existing = claimCampaignRepository.findByUserIdAndCampaignId(request.getUserId(), request.getCampaignId());
        if (existing.isPresent())
            return mapper.toDTO(existing.get());

        campaignRepository.findById(request.getCampaignId())
                .orElseThrow(() -> new NoSuchCampaignException("Campaign not found."));

        var user = campaignUserRepository.findById(request.getUserId())
                .orElseThrow(() -> new NoSuchUserException("User not found"));

        var claimCampaign = ClaimCampaign.builder()
                .campaignId(request.getCampaignId())
                .userId(request.getUserId())
                .claimDate(request.getClaimDate())
                .status(ClaimCampaignStatus.SUBMITTED)
                .build();
        var saved = claimCampaignRepository.save(claimCampaign);

        var claimSubmission = ClaimSubmissionDTO.builder()
                .claimDate(request.getClaimDate())
                .campaignId(request.getCampaignId())
                .userId(request.getUserId())
                .userPoints(user.getLoyaltyPoints())
                .build();

        redisServiceHelper.sendClaimToPriorityQueue(claimSubmission);

        return mapper.toDTO(saved);
    }


    /**
     * Trigger every 5 seconds is to check if any claim submitted
     *
     */
    @Scheduled(fixedRate = 5000)
    protected void executeClaimCampaignSubmission() {
        long start = System.currentTimeMillis();
        List<Long> campaignIds = redisServiceHelper.getCampaignIdsForClaimCheck();

        int processedCount = 0;
        for (var campaignId : campaignIds) {
            processedCount += processClaimCheck(campaignId);
        }

        log.info("ExecuteClaimCheckJob - Finished at {} - processed {} claims - took {} seconds", LocalDateTime.now(), processedCount, (System.currentTimeMillis() - start) / 1000.0);
    }

    protected int processClaimCheck(Long campaignId) {
        int processedCount = 0;
        try {
            String redisKey = AppConstants.CLAIM_CAMPAIGN_PRIORITY_ZSET + campaignId;
            var zsetOps = redisTemplate.opsForZSet();

            while (zsetOps.zCard(redisKey) > 0) {
                var tupleList = zsetOps.popMax(redisKey, Math.min(zsetOps.zCard(redisKey), batchSize));
                for (var tuple : tupleList) {
                    log.info("Process claim detail: campaign_id {} - claim_date {} - loyalty_points#user_id {}", campaignId, convertDateTime(tuple.getScore().longValue()), tuple.getValue());
                    processClaim(campaignId, extractUserId(tuple.getValue()));
                    processedCount++;
                }
            }
        } catch (Exception ex) {
            log.error("Error campaign_id " + campaignId, ex);
        }

        return processedCount;
    }

    protected void processClaim(Long campaignId, Long userId) {
        ClaimCampaign claimCampaign = claimCampaignRepository.findByUserIdAndCampaignId(userId, campaignId).orElse(null);
        if (claimCampaign == null
                || ClaimCampaignStatus.SUBMITTED != claimCampaign.getStatus()) {
            log.warn("Claim_campaign with campaign_id {} and user_id {} is not found, or not a SUBMITTED status", campaignId, userId);
            return;
        }

        try {
            redisServiceHelper.reserveCampaignSeat(campaignId)
                    .orElseThrow(() -> new CampaignNoAvailableSeatsException(String.format("No available seats for campaign id %s and user id %s", campaignId, userId)));

            redisServiceHelper.addUserToCampaignMembers(campaignId, userId)
                    .orElseThrow(() -> new CampaignClaimedAlreadyException(String.format("Campaign ID %s has been claimed already by the user ID %s ", campaignId, userId)));

            claimCampaign.setStatus(ClaimCampaignStatus.APPROVED);

        } catch (Exception ex) {
            log.error("Error ", ex);
            claimCampaign.setStatus(ClaimCampaignStatus.REJECTED);
            claimCampaign.setReason(ex.getMessage()); // TODO: should do a better way
        }

        claimCampaignRepository.save(claimCampaign);
    }

    private Long extractUserId(String serializeRedisValue) {
        String[] arrays = serializeRedisValue.split("#");
        return arrays.length == 2 ? Long.parseLong(arrays[1]) : null;
    }

    private LocalDateTime convertDateTime(Long reverseTimestamp){
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.MAX_VALUE - reverseTimestamp), ZoneOffset.UTC);
    }
}
