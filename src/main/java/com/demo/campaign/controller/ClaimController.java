package com.demo.campaign.controller;

import com.demo.campaign.dto.ClaimCampaignDTO;
import com.demo.campaign.dto.CreateClaimCampaign;
import com.demo.campaign.repository.ClaimCampaignRepository;
import com.demo.campaign.service.ClaimCampaignService;
import com.demo.campaign.service.exception.NoSuchClaimCampaignException;
import com.demo.campaign.service.mapper.MapperConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/claim")
@Tag(name = "Claim Controller")
public class ClaimController {

    private final ClaimCampaignService claimCampaignService;
    private final ClaimCampaignRepository claimCampaignRepository;
    private final MapperConfig mapperConfig;

    public ClaimController(ClaimCampaignService claimCampaignService,
                           ClaimCampaignRepository claimCampaignRepository,
                           MapperConfig mapperConfig) {
        this.claimCampaignService = claimCampaignService;
        this.claimCampaignRepository = claimCampaignRepository;
        this.mapperConfig = mapperConfig;
    }

    @PostMapping("")
    @Operation(summary = "The API is to submit a claim campaign")
    public ResponseEntity<ClaimCampaignDTO> claimCampaign(@RequestBody @Valid CreateClaimCampaign request) {
        ClaimCampaignDTO result = claimCampaignService.claimCampaign(request);
        return ResponseEntity.ok(result);
    }

    @GetMapping("")
    @Operation(summary = "The API is to query the status of a submitted claim campaign")
    public ResponseEntity<ClaimCampaignDTO> getClaimCampaignStatus(@RequestParam Long userId, @RequestParam Long campaignId) {
        ClaimCampaignDTO dto = claimCampaignRepository.findByUserIdAndCampaignId(userId, campaignId)
                .map(mapperConfig::toDTO)
                .orElseThrow(() -> new NoSuchClaimCampaignException("The user has not claimed the campaign yet."));
        return ResponseEntity.ok(dto);
    }
}
