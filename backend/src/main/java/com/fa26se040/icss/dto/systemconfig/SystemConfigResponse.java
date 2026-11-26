package com.fa26se040.icss.dto.systemconfig;

import com.fa26se040.icss.dto.area.EventNightAdjustment;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * @param eventAdjustments EV-N8 (V115): chỉ có khi lưu AI_AFTER_HOUR_START / AI_AFTER_HOUR_END — các lịch / sự kiện hệ thống
 *                         đã cắt / huỷ / tắt theo khung ban đêm mới (rỗng nếu không có gì bị ảnh hưởng); null ở mọi trường hợp khác
 * @param options          V150: danh sách lựa chọn hợp lệ (value, nhãn tiếng Việt, mô tả) cho khoá dạng chọn nhiều; null ở mọi khoá khác
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
    String tooltip,
    Boolean editable,
    OffsetDateTime updatedAt,
    String updatedByEmail,
    String updatedByName,
    List<EventNightAdjustment> eventAdjustments,
    List<ConfigOptionResponse> options
) {
    public SystemConfigResponse(String configKey, String configValue, String dataType, BigDecimal minValue,
                                BigDecimal maxValue, String unit, String configGroup, Integer displayOrder,
                                String description, Boolean editable, OffsetDateTime updatedAt,
                                String updatedByEmail, String updatedByName) {
        this(configKey, configValue, dataType, minValue, maxValue, unit, configGroup, displayOrder, description,
                null, editable, updatedAt, updatedByEmail, updatedByName, null, null);
    }

    public SystemConfigResponse(String configKey, String configValue, String dataType, BigDecimal minValue,
                                BigDecimal maxValue, String unit, String configGroup, Integer displayOrder,
                                String description, String tooltip, Boolean editable, OffsetDateTime updatedAt,
                                String updatedByEmail, String updatedByName) {
        this(configKey, configValue, dataType, minValue, maxValue, unit, configGroup, displayOrder, description,
                tooltip, editable, updatedAt, updatedByEmail, updatedByName, null, null);
    }

    public SystemConfigResponse(String configKey, String configValue, String dataType, BigDecimal minValue,
                                BigDecimal maxValue, String unit, String configGroup, Integer displayOrder,
                                String description, String tooltip, Boolean editable, OffsetDateTime updatedAt,
                                String updatedByEmail, String updatedByName, List<EventNightAdjustment> eventAdjustments) {
        this(configKey, configValue, dataType, minValue, maxValue, unit, configGroup, displayOrder, description,
                tooltip, editable, updatedAt, updatedByEmail, updatedByName, eventAdjustments, null);
    }

    public SystemConfigResponse withEventAdjustments(List<EventNightAdjustment> adjustments) {
        return new SystemConfigResponse(configKey, configValue, dataType, minValue, maxValue, unit, configGroup,
                displayOrder, description, tooltip, editable, updatedAt, updatedByEmail, updatedByName, adjustments, options);
    }
}
