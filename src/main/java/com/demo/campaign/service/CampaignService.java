package com.demo.campaign.service;

import com.demo.campaign.dto.CampaignDTO;
import com.demo.campaign.dto.CreateCampaign;

import java.util.Collection;

public interface CampaignService {

    CampaignDTO createCampaign(CreateCampaign request);

    Collection<CampaignDTO> getCampaigns();
}
