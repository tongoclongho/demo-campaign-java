package com.demo.campaign.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateCampaign(
        @NotBlank String campaignCode,
        @NotNull @Min(1) Integer totalCapacity) {
}
