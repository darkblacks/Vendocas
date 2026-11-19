package com.platform.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final TenantInterceptor tenantInterceptor;
    private final PlatformAdminInterceptor adminInterceptor;

    public WebConfig(TenantInterceptor tenantInterceptor, PlatformAdminInterceptor adminInterceptor) {
        this.tenantInterceptor = tenantInterceptor;
        this.adminInterceptor = adminInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantInterceptor).addPathPatterns("/api/v1/**");
        registry.addInterceptor(adminInterceptor).addPathPatterns("/api/v1/admin/**");
    }
}
