package com.kumouri.kmodigipresbe.config;

import com.kumouri.kmodigipresbe.config.conversion.BigDecimalConverters;
import com.kumouri.kmodigipresbe.config.conversion.InternetAddressConverters;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedSimpleReactiveMongoRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableReactiveMongoAuditing;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;

import java.util.List;

@Configuration
@EnableReactiveMongoAuditing
@EnableReactiveMongoRepositories(
        basePackages = "com.kumouri.kmodigipresbe",
        repositoryBaseClass = TenantScopedSimpleReactiveMongoRepository.class
)
public class MongoConfig {

    /**
     * Custom Mongo converters. The defaults Spring Data ships persist
     * {@link java.math.BigDecimal} as a BSON String (breaks aggregations) and
     * leave {@code jakarta.mail.internet.InternetAddress} to the POJO codec
     * (round-trip loss). Both are corrected here. See the per-class Javadoc
     * for the bug histories.
     */
    @Bean
    public MongoCustomConversions customConversions() {
        return new MongoCustomConversions(List.of(
                BigDecimalConverters.BigDecimalToDecimal128.INSTANCE,
                BigDecimalConverters.Decimal128ToBigDecimal.INSTANCE,
                BigDecimalConverters.StringToBigDecimal.INSTANCE,
                InternetAddressConverters.InternetAddressToString.INSTANCE,
                InternetAddressConverters.StringToInternetAddress.INSTANCE));
    }
}
