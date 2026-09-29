package com.fa26se040.icss.dto.reasoncatalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReasonCatalogUpdateRequest(
        @NotBlank(message = "Nhãn lý do không được để trống")
        @Size(max = 255, message = "Nhãn lý do tối đa 255 ký tự")
        String label,

        Integer sortOrder
) {}
