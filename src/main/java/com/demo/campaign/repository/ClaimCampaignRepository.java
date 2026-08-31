package com.demo.campaign.repository;

import com.demo.campaign.dto.CampaignUsedSeats;
import com.demo.campaign.entity.ClaimCampaign;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Transactional(readOnly = true)
public interface ClaimCampaignRepository extends CrudRepository<ClaimCampaign, Long> {
    Optional<ClaimCampaign> findByUserIdAndCampaignId(Long userId, Long campaignId);

    @Query(value = "SELECT * FROM claim_campaign WHERE campaign_id = :campaignId AND status = :status", nativeQuery = true)
    List<ClaimCampaign> getClaimCampaignBy(@Param("campaignId") Long campaignId, @Param("status") String status);

    @Query(
            value = "SELECT campaign_id AS campaignId, COUNT(*) AS usedSeats " +
                    "FROM claim_campaign WHERE campaign_id IN :campaignIds AND status='APPROVED' " +
                    "GROUP BY campaign_id",
            nativeQuery = true
    )
    List<CampaignUsedSeats> aggregateCampaignUsedSeats(@Param("campaignIds") Set<Long> campaignIds);
}
