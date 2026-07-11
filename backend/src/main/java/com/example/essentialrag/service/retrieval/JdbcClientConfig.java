package com.example.essentialrag.service.retrieval;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration
public class JdbcClientConfig {

  @Bean
  @ConditionalOnMissingBean(JdbcClient.class)
  JdbcClient jdbcClient(JdbcTemplate jdbcTemplate) {
    return JdbcClient.create(jdbcTemplate);
  }
}
