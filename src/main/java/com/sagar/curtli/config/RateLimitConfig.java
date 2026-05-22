package com.sagar.curtli.config;

import com.sagar.curtli.filter.RateLimitFilter;
import com.sagar.curtli.service.RateLimiterService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimitConfig {

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimiterService rateLimiterService) {
        FilterRegistrationBean<RateLimitFilter> registrationBean = new FilterRegistrationBean<>();

        // Inject the service into the filter manually
        registrationBean.setFilter(new RateLimitFilter(rateLimiterService));

        // Rate-limit the single write endpoint. (/api/bulk-shorten was removed —
        // /api/shorten now takes an array.)
        registrationBean.addUrlPatterns("/api/shorten");

        // Set it to run early in the filter chain
        registrationBean.setOrder(1);

        return registrationBean;
    }
}