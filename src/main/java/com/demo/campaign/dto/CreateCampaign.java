package com.demo.campaign.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateCampaign(
        @NotBlank String campaignCode,
        @Min(1) Integer totalCapacity) {
}
