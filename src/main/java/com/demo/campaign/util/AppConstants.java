package com.demo.campaign.util;

public interface AppConstants {

    /**
     * campaign_members_set#{campaignId} -> Set.of("userId")
     */
    String CAMPAIGN_MEMBERS_SET = "campaign_members_set#";

    /**
     * campaign_available_seats#{campaignId} -> remainingSeats
     */
    String CAMPAIGN_AVAILABLE_SEATS = "campaign_available_seats#";

    /**
     * claim_campaign_priority_zset#{campaignId} -> SortedSet of "loyaltyPoints#userId" with score = LocalDateTime.toEpocMillis()
     */
    String CLAIM_CAMPAIGN_PRIORITY_ZSET = "claim_campaign_priority_set#";

    /**
     *
     */
    String CLAIM_CAMPAIGN_PRIORITY_IDS_SET = "claim_campaign_priority_ids_set";
}
