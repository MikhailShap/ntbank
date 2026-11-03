package ru.ntbank.bank.web;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.ntbank.bank.async.TransferDispatcher;
import ru.ntbank.bank.client.AntifraudClient;
import ru.ntbank.bank.config.AppProperties;
import ru.ntbank.bank.exception.BusinessException;
import ru.ntbank.bank.repo.TransferRepository;
import ru.ntbank.bank.service.RuntimeInfoService;
import ru.ntbank.bank.service.SeedService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Служебная страница стенда: статистика и сброс тестовых данных. */
@Controller
public class AdminController {

    private final SeedService seedService;
    private final JdbcTemplate jdbc;
    private final TransferRepository transfers;
    private final TransferDispatcher dispatcher;
    private final AntifraudClient antifraud;
    private final RuntimeInfoService runtimeInfo;
    private final AppProperties properties;

    public AdminController(SeedService seedService, JdbcTemplate jdbc, TransferRepository transfers,
                           TransferDispatcher dispatcher, AntifraudClient antifraud,
                           RuntimeInfoService runtimeInfo, AppProperties properties) {
        this.seedService = seedService;
        this.jdbc = jdbc;
        this.transfers = transfers;
        this.dispatcher = dispatcher;
        this.antifraud = antifraud;
        this.runtimeInfo = runtimeInfo;
        this.properties = properties;
    }

    @GetMapping("/admin")
    public String admin(Model model) {
        model.addAttribute("stats", collectStats());
        return "admin";
    }

    @PostMapping("/admin/reset")
    public String reset(RedirectAttributes redirect) {
        SeedService.SeedResult result;
        try {
            result = seedService.reset();
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin";
        }
        SeedService.HistoryResult history = seedService.growToConfiguredTotal();
        long operations = history != null ? history.after() : result.operations();
        long elapsed = result.elapsedMs() + (history != null ? history.elapsedMs() : 0);
        redirect.addFlashAttribute("success", "Данные пересозданы за " + elapsed
                + " мс: пользователей " + result.users()
                + ", счетов " + result.accounts()
                + ", операций " + operations);
        return "redirect:/admin";
    }

    /** Нарастить историю операций — для дефекта «Нет индекса» на реалистичном объёме. */
    @PostMapping("/admin/history")
    public String growHistory(@RequestParam long total, RedirectAttributes redirect) {
        try {
            SeedService.HistoryResult result = seedService.growHistory(total);
            redirect.addFlashAttribute("success", result.added() == 0
                    ? "В базе уже " + result.before() + " операций, наращивать нечего"
                    : "История наращена с " + result.before() + " до " + result.after()
                      + " операций за " + (result.elapsedMs() / 1000) + " с");
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin";
    }

    private Map<String, Object> collectStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("users", count("app_user"));
        stats.put("accounts", count("account"));
        stats.put("operations", count("operation"));
        stats.put("services", count("service_catalog"));
        stats.put("asyncMode", dispatcher.mode());
        stats.put("antifraudMode", antifraud.mode());
        stats.put("poolSize", runtimeInfo.hikariMaxPoolSize());
        stats.put("tomcatThreads", runtimeInfo.tomcatMaxThreads());
        stats.put("csvPath", properties.seed().csvPath());
        stats.put("configuredOperations", properties.seed().operationsTotal());

        Map<String, Long> byStatus = new LinkedHashMap<>();
        List<Object[]> rows = transfers.countGroupedByStatus();
        for (Object[] row : rows) {
            byStatus.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        stats.put("transfersByStatus", byStatus);
        return stats;
    }

    private long count(String table) {
        Long value = jdbc.queryForObject("select count(*) from " + table, Long.class);
        return value == null ? 0 : value;
    }
}
