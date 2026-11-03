package ru.ntbank.bank.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    public static final String ADMIN_SCHEME = "admin-basic";

    @Bean
    public OpenAPI ntBankOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("НТ-Банк — REST API")
                        .version("1.0.0")
                        .description("""
                                Учебный стенд для практики нагрузочного тестирования.

                                Тестовые пользователи: `user0001` … `user1000`, пароль у всех `Passw0rd!`.
                                Телефон клиента получается из номера логина: `user0042` — `+79000000042`.
                                """))
                .servers(List.of(new Server().url("/").description("Локальный стенд")))
                .components(new Components()
                        .addSecuritySchemes("bearer-jwt", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT"))
                        // админский REST — по логину и паролю admin/admin, а не по токену клиента
                        .addSecuritySchemes(ADMIN_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("basic")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
    }
}
