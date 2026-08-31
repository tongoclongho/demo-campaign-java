package com.demo.campaign.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@AllArgsConstructor
@Setter
@Getter
@Builder
public class CampaignDTO {

    private Long campaignId;
    private String campaignCode;
    private Integer totalCapacity;
    private int usedSeats;
}
