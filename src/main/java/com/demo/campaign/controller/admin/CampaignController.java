package com.demo.campaign.controller.admin;

import com.demo.campaign.dto.CampaignDTO;
import com.demo.campaign.dto.CreateCampaign;
import com.demo.campaign.service.CampaignService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;

@RestController
@RequestMapping("/api/v1/campaign")
@Tag(name = "Campaign Controller")
public class CampaignController {

    private final CampaignService campaignService;

    public CampaignController(CampaignService campaignService) {
        this.campaignService = campaignService;
    }

    @GetMapping("")
    @Operation(summary = "Get all campaigns")
    public ResponseEntity<Collection<CampaignDTO>> getCampaign() {
        return ResponseEntity.ok(campaignService.getCampaigns());
    }

    @PostMapping("")
    @Operation(summary = "Create a campaign")
    public ResponseEntity<CampaignDTO> createCampaign(@RequestBody @Valid CreateCampaign request) {
        CampaignDTO created = campaignService.createCampaign(request);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }
}
