package dev.tape;

import dev.tape.application.*;
import dev.tape.persistence.PostgresJournal;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.time.Clock;
import java.util.UUID;

@SpringBootApplication
public class ExchangeApplication {
    public static void main(String[] args) { SpringApplication.run(ExchangeApplication.class, args); }
    @Bean Journal journal(DataSource dataSource) { return new PostgresJournal(dataSource); }
    @Bean @DependsOnDatabaseInitialization
    ExchangeService exchangeService(Journal journal) { return new ExchangeService(journal, UUID::randomUUID, Clock.systemUTC()); }
    @Bean JsonMapper jsonMapper() { return strictJsonMapper(); }
    public static JsonMapper strictJsonMapper() {
        return JsonMapper.builder().findAndAddModules()
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .withCoercionConfig(LogicalType.Integer, config -> config.setCoercion(CoercionInputShape.String, CoercionAction.Fail))
                .build();
    }
}
