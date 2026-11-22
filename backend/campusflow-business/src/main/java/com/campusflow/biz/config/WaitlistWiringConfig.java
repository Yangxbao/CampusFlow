package com.campusflow.biz.config;

import com.campusflow.biz.registration.service.RegistrationService;
import com.campusflow.biz.waitlist.service.WaitlistPromoteService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

/**
 * 跨域装配：取消报名后的候补晋级触发入口。
 * <p>用函数注入而非直接 @Autowired 引用，打破 RegistrationService -> WaitlistPromoteService
 * 与 WaitlistService -> RegistrationService 之间的循环依赖（模块内边界保持单向）。
 */
@Configuration
@RequiredArgsConstructor
public class WaitlistWiringConfig {

    private final RegistrationService registrationService;
    private final WaitlistPromoteService waitlistPromoteService;

    @PostConstruct
    public void wire() {
        registrationService.setWaitlistPromoteRunner(waitlistPromoteService::tryPromoteNext);
    }
}
