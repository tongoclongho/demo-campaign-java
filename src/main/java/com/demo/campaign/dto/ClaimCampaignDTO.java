package com.demo.campaign.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@AllArgsConstructor
@NoArgsConstructor
@Setter
@Getter
@Builder
public class ClaimCampaignDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = -2151312099112384459L;

    private Long userId;
    private Long campaignId;
    private LocalDateTime claimDate;
    private String status;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String reason;
}
