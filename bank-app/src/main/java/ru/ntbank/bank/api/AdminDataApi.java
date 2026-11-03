package ru.ntbank.bank.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import ru.ntbank.bank.config.OpenApiConfig;
import ru.ntbank.bank.service.SeedService;

/** Объём тестовых данных. Доступ по HTTP Basic (admin / admin). */
@RestController
@RequestMapping("/api/admin/history")
@Tag(name = "6. Режим «Дефекты» (admin/admin)")
@SecurityRequirement(name = OpenApiConfig.ADMIN_SCHEME)
public class AdminDataApi {

    private final SeedService seedService;

    public AdminDataApi(SeedService seedService) {
        this.seedService = seedService;
    }

    @PostMapping
    @Operation(summary = "Нарастить историю операций до заданного числа строк",
            description = "Например total=2000000. Максимум 10 000 000. "
                    + "На 2 млн операций занимает около минуты.")
    public SeedService.HistoryResult grow(@RequestParam long total) {
        return seedService.growHistory(total);
    }
}
