package com.fa26se040.icss.dto.reasoncatalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ReasonCatalogCreateRequest(
        @NotBlank(message = "Loại thao tác không được để trống")
        @Pattern(regexp = "^(EVENT_ENABLE|EVENT_DISABLE|EVENT_EXTEND)$", message = "Loại thao tác phải là EVENT_ENABLE, EVENT_DISABLE hoặc EVENT_EXTEND")
        String actionType,

        @NotBlank(message = "Mã lý do không được để trống")
        @Size(max = 50, message = "Mã lý do tối đa 50 ký tự")
        @Pattern(regexp = "^[A-Z0-9_]+$", message = "Mã lý do chỉ chứa chữ hoa, số và dấu gạch dưới")
        String code,

        @NotBlank(message = "Nhãn lý do không được để trống")
        @Size(max = 255, message = "Nhãn lý do tối đa 255 ký tự")
        String label,

        Boolean isOther,

        Integer sortOrder
) {
    public ReasonCatalogCreateRequest(String actionType, String code, String label, Integer sortOrder) {
        this(actionType, code, label, false, sortOrder);
    }
}
