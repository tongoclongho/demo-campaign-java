package com.demo.campaign.service;

import com.demo.campaign.dto.ClaimCampaignDTO;
import com.demo.campaign.dto.CreateClaimCampaign;
import jakarta.validation.Valid;

public interface ClaimCampaignService {
    ClaimCampaignDTO claimCampaign(@Valid CreateClaimCampaign request);
}
