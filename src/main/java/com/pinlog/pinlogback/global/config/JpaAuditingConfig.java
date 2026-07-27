package com.pinlog.pinlogback.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Spring Data JPA 감사 기능을 켠다.
 * 이 설정이 없으면 BaseEntity의 @CreatedDate가 채워지지 않아 created_at NOT NULL 제약을 위반한다.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
