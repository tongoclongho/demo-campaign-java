package com.demo.campaign.service;

import com.demo.campaign.entity.ClaimCampaign;
import com.demo.campaign.entity.ClaimCampaignStatus;
import com.demo.campaign.repository.CampaignRepository;
import com.demo.campaign.repository.ClaimCampaignRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class CacheManager {

    private final CampaignRepository campaignRepository;

    private final ClaimCampaignRepository claimCampaignRepository;

    private final RedisServiceHelper redisServiceHelper;

    public CacheManager(CampaignRepository campaignRepository,
                        ClaimCampaignRepository claimCampaignRepository,
                        RedisServiceHelper redisServiceHelper) {
        this.campaignRepository = campaignRepository;
        this.claimCampaignRepository = claimCampaignRepository;
        this.redisServiceHelper = redisServiceHelper;
    }

    @Scheduled(fixedRate = 5000) //Trigger every 5s
    public void refreshRedisCache(){
        long start = System.currentTimeMillis();
        for (var campaign : campaignRepository.findAll()) {

            List<Long> userIds = claimCampaignRepository.getClaimCampaignBy(campaign.getId(), ClaimCampaignStatus.APPROVED.toString())
                    .stream().map(ClaimCampaign::getUserId).toList();

            redisServiceHelper.refreshCampaignMembersCache(campaign.getId(), userIds);

            redisServiceHelper.refreshCampaignAvailableSeatsCache(campaign.getId(), campaign.getTotalCapacity() - userIds.size());

        }

        log.info("Refresh redis cache - Finished at {} took {} seconds", LocalDateTime.now(), (System.currentTimeMillis() - start) / 1000.0);
    }

}
