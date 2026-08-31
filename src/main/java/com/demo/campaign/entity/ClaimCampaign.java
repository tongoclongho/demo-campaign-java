package com.demo.campaign.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClaimCampaign extends BaseEntity {

    private Long campaignId;
    private Long userId;
    private LocalDateTime claimDate;

    @Enumerated(EnumType.STRING)
    private ClaimCampaignStatus status;

    private String reason;

}
