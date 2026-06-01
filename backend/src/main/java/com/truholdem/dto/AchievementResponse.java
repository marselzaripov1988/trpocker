package com.truholdem.dto;

import java.util.UUID;

import com.truholdem.model.Achievement;

/**
 * Read-model for an achievement definition (Phase 6). Decouples the REST API from the
 * {@link Achievement} JPA entity, serializing to the identical JSON shape ({@code isHidden()} → {@code hidden}).
 */
public record AchievementResponse(
        UUID id,
        String code,
        String name,
        String description,
        String icon,
        String category,
        int points,
        String requirementType,
        int requirementValue,
        boolean hidden) {

    public static AchievementResponse from(Achievement a) {
        return new AchievementResponse(
                a.getId(),
                a.getCode(),
                a.getName(),
                a.getDescription(),
                a.getIcon(),
                a.getCategory(),
                a.getPoints(),
                a.getRequirementType(),
                a.getRequirementValue(),
                a.isHidden());
    }
}
