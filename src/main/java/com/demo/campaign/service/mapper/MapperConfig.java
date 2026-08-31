package com.demo.campaign.service.mapper;

import com.demo.campaign.dto.CampaignDTO;
import com.demo.campaign.dto.ClaimCampaignDTO;
import com.demo.campaign.entity.Campaign;
import com.demo.campaign.entity.ClaimCampaign;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface MapperConfig {

    @Mapping(target = "campaignId", source = "entity.id")
    @Mapping(target = "campaignCode", source = "entity.code")
    CampaignDTO toDTO(Campaign entity);

    ClaimCampaignDTO toDTO(ClaimCampaign entity);
}
