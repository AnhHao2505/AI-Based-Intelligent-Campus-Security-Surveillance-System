package com.fa26se040.icss.dto.systemconfig;

import com.fa26se040.icss.dto.area.EventNightAdjustment;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * @param eventAdjustments EV-N8 (V115): chỉ có khi lưu AI_AFTER_HOUR_START / AI_AFTER_HOUR_END — các lịch / sự kiện hệ thống
 *                         đã cắt / huỷ / tắt theo khung ban đêm mới (rỗng nếu không có gì bị ảnh hưởng); null ở mọi trường hợp khác
 */
public record SystemConfigResponse(
    String configKey,
    String configValue,
    String dataType,
    BigDecimal minValue,
    BigDecimal maxValue,
    String unit,
    String configGroup,
    Integer displayOrder,
    String description,
    Boolean editable,
    OffsetDateTime updatedAt,
    String updatedByEmail,
    String updatedByName,
    List<EventNightAdjustment> eventAdjustments
) {
    public SystemConfigResponse(String configKey, String configValue, String dataType, BigDecimal minValue,
                                BigDecimal maxValue, String unit, String configGroup, Integer displayOrder,
                                String description, Boolean editable, OffsetDateTime updatedAt,
                                String updatedByEmail, String updatedByName) {
        this(configKey, configValue, dataType, minValue, maxValue, unit, configGroup, displayOrder, description,
                editable, updatedAt, updatedByEmail, updatedByName, null);
    }

    public SystemConfigResponse withEventAdjustments(List<EventNightAdjustment> adjustments) {
        return new SystemConfigResponse(configKey, configValue, dataType, minValue, maxValue, unit, configGroup,
                displayOrder, description, editable, updatedAt, updatedByEmail, updatedByName, adjustments);
    }
}
