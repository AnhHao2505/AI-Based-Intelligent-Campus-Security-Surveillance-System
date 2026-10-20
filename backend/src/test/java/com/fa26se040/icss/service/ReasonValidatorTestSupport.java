package com.fa26se040.icss.service;

import com.fa26se040.icss.enums.ConfigKey;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

/**
 * ReasonValidator đọc giới hạn mặc định khai báo ở ConfigKey (REASON_MIN_LENGTH / REASON_MAX_LENGTH),
 * cho test Mockito dựng service bằng {@code @InjectMocks}: khai báo {@code @Spy ReasonValidator reasonValidator = withDefaults()}.
 */
public final class ReasonValidatorTestSupport {

    private ReasonValidatorTestSupport() {
    }

    public static ReasonValidator withDefaults() {
        Answer<Object> configDefaults = invocation -> {
            if ("getInt".equals(invocation.getMethod().getName())
                    && invocation.getArguments().length == 1
                    && invocation.getArgument(0) instanceof ConfigKey key) {
                return Integer.parseInt(key.getDefaultValue());
            }
            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        };
        return new ReasonValidator(Mockito.mock(SystemConfigService.class, configDefaults));
    }
}
