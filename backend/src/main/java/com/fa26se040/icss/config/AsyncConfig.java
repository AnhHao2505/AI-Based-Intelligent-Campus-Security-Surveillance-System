package com.fa26se040.icss.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * M-04b: gửi e-mail chạy nền trên pool riêng để request (tạo tài khoản, nạp hàng loạt, quên mật khẩu)
 * không chờ SMTP. Các hàm {@code @Async} phải ghi rõ {@link #MAIL_EXECUTOR}: context còn có taskScheduler
 * của {@code @EnableScheduling} nên không có executor mặc định duy nhất.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String MAIL_EXECUTOR = "mailExecutor";

    @Bean(name = MAIL_EXECUTOR)
    public ThreadPoolTaskExecutor mailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("mail-");
        // Tắt ứng dụng: gửi nốt mail đã xếp hàng (tối đa 10 giây) thay vì bỏ ngang
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
