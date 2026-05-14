package com.kumouri.kmodigipresbe.config;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedSimpleReactiveMongoRepository;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableReactiveMongoAuditing;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;

@Configuration
@EnableReactiveMongoAuditing
@EnableReactiveMongoRepositories(
        basePackages = "com.kumouri.kmodigipresbe",
        repositoryBaseClass = TenantScopedSimpleReactiveMongoRepository.class
)
public class MongoConfig {
}
