package com.demo.campaign.service;

import com.demo.campaign.dto.CampaignDTO;
import com.demo.campaign.dto.CreateCampaign;
import com.demo.campaign.entity.Campaign;
import com.demo.campaign.repository.CampaignRepository;
import com.demo.campaign.repository.ClaimCampaignRepository;
import com.demo.campaign.service.exception.ExistingCampaignCodeException;
import com.demo.campaign.service.mapper.MapperConfig;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;

@Service
@Slf4j
public class CampaignServiceImpl implements CampaignService {

    private final CampaignRepository campaignRepository;
    private final ClaimCampaignRepository claimCampaignRepository;
    private final MapperConfig mapper;

    public CampaignServiceImpl(CampaignRepository campaignRepository,
                               ClaimCampaignRepository claimCampaignRepository,
                               MapperConfig mapper) {
        this.campaignRepository = campaignRepository;
        this.claimCampaignRepository = claimCampaignRepository;
        this.mapper = mapper;
    }


    @Override
    public CampaignDTO createCampaign(@Valid CreateCampaign request) {
        String codeUpperCase = request.campaignCode().trim().toUpperCase();
        Campaign campaign = Campaign.builder()
                .code(codeUpperCase)
                .totalCapacity(request.totalCapacity())
                .build();

        if (campaignRepository.existingCampaignCode(codeUpperCase) > 0)
            throw new ExistingCampaignCodeException("Existing campaign code " + codeUpperCase);

        return mapper.toDTO(campaignRepository.save(campaign));
    }

    @Override
    public Collection<CampaignDTO> getCampaigns() {
        var map = new HashMap<Long, CampaignDTO>();
        campaignRepository.findAll()
                .forEach(campaign -> map.put(campaign.getId(), mapper.toDTO(campaign)));

        var usedSeats = claimCampaignRepository.aggregateCampaignUsedSeats(map.keySet());

        for (var itm : usedSeats) {
            CampaignDTO dto = map.get(itm.campaignId());
            if (dto != null && itm.usedSeats() != null) {
                dto.setUsedSeats(itm.usedSeats().intValue());
            }
        }
        return map.values();
    }
}
