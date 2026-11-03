package ru.ntbank.bank.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.ntbank.bank.chaos.ChaosService;
import ru.ntbank.bank.chaos.ChaosSettings;
import ru.ntbank.bank.chaos.ChaosTarget;
import ru.ntbank.bank.chaos.SlowSqlMode;

/**
 * Страница «Дефекты». Каждая карточка — отдельная форма со своей кнопкой «Применить»,
 * поэтому один дефект можно менять, не трогая остальные.
 * Снятый флажок приходит как отсутствующий параметр, отсюда defaultValue = "false".
 */
@Controller
@RequestMapping("/admin/chaos")
public class ChaosWebController {

    private static final String REDIRECT = "redirect:/admin/chaos";

    private final ChaosService chaosService;
    private final ChaosSettings settings;

    public ChaosWebController(ChaosService chaosService, ChaosSettings settings) {
        this.chaosService = chaosService;
        this.settings = settings;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("chaos", chaosService.state());
        model.addAttribute("targets", ChaosTarget.values());
        model.addAttribute("slowSqlModes", SlowSqlMode.values());
        return "chaos";
    }

    @PostMapping("/latency")
    public String latency(@RequestParam(defaultValue = "false") boolean enabled,
                          @RequestParam(required = false) String target,
                          @RequestParam(required = false) Long minMs,
                          @RequestParam(required = false) Long maxMs,
                          RedirectAttributes redirect) {
        settings.applyLatency(enabled, ChaosTarget.of(target, ChaosTarget.ALL), minMs, maxMs);
        return done(redirect, enabled, "Задержка ответа");
    }

    @PostMapping("/errors")
    public String errors(@RequestParam(defaultValue = "false") boolean enabled,
                         @RequestParam(required = false) String target,
                         @RequestParam(required = false) Integer percent,
                         RedirectAttributes redirect) {
        settings.applyErrors(enabled, ChaosTarget.of(target, ChaosTarget.ALL), percent);
        return done(redirect, enabled, "Ошибки 500");
    }

    @PostMapping("/slow-sql")
    public String slowSql(@RequestParam(defaultValue = "false") boolean enabled,
                          @RequestParam(required = false) String mode,
                          RedirectAttributes redirect) {
        SlowSqlMode selected = SlowSqlMode.of(mode, settings.getSlowSqlMode());
        chaosService.applySlowSql(enabled, selected);
        return done(redirect, enabled, "Медленный SQL («" + selected.getTitle() + "»)");
    }

    @PostMapping("/leak")
    public String leak(@RequestParam(defaultValue = "false") boolean enabled,
                       @RequestParam(required = false) String target,
                       @RequestParam(required = false) Integer kbPerRequest,
                       RedirectAttributes redirect) {
        settings.applyLeak(enabled, ChaosTarget.of(target, ChaosTarget.ALL), kbPerRequest);
        return done(redirect, enabled, "Утечка памяти");
    }

    @PostMapping("/leak/clear")
    public String clearLeak(RedirectAttributes redirect) {
        chaosService.clearLeaked();
        redirect.addFlashAttribute("success", "Накопленные объекты утечки очищены");
        return REDIRECT;
    }

    @PostMapping("/cpu")
    public String cpu(@RequestParam(defaultValue = "false") boolean enabled,
                      @RequestParam(required = false) String target,
                      @RequestParam(required = false) Integer millis,
                      RedirectAttributes redirect) {
        settings.applyCpu(enabled, ChaosTarget.of(target, ChaosTarget.ALL), millis);
        return done(redirect, enabled, "Нагрузка на CPU");
    }

    @PostMapping("/pools")
    public String pools(@RequestParam(required = false) Integer hikariMaxPoolSize,
                        @RequestParam(required = false) Integer tomcatMaxThreads,
                        RedirectAttributes redirect) {
        chaosService.applyPools(hikariMaxPoolSize, tomcatMaxThreads);
        ChaosService.ChaosState state = chaosService.state();
        redirect.addFlashAttribute("success", "Размеры пулов применены: HikariCP "
                + state.hikariMaxPoolSize() + ", потоки Tomcat " + state.tomcatMaxThreads());
        return REDIRECT;
    }

    @PostMapping("/antifraud")
    public String antifraud(@RequestParam(defaultValue = "false") boolean enabled,
                            @RequestParam(required = false) Long delayMinMs,
                            @RequestParam(required = false) Long delayMaxMs,
                            @RequestParam(required = false) Integer errorPercent,
                            @RequestParam(required = false) Integer rejectPercent,
                            RedirectAttributes redirect) {
        boolean applied = chaosService.applyAntifraud(enabled, delayMinMs, delayMaxMs,
                errorPercent, rejectPercent);
        if (!applied) {
            redirect.addFlashAttribute("error", "Внешний сервис «Антифрод» не подключён: "
                    + "в этом запуске работают локальные правила, настройки применить некуда");
        } else {
            done(redirect, enabled, "Дефекты «Антифрода»");
        }
        return REDIRECT;
    }

    @PostMapping("/kafka")
    public String kafka(@RequestParam(defaultValue = "false") boolean paused,
                        @RequestParam(required = false) Long consumerDelayMs,
                        RedirectAttributes redirect) {
        chaosService.applyKafka(paused, consumerDelayMs);
        if (!chaosService.kafkaAvailable()) {
            redirect.addFlashAttribute("error", "Kafka в этом запуске не используется: "
                    + "переводы обрабатывает внутренняя очередь в памяти");
        } else {
            redirect.addFlashAttribute("success", paused
                    ? "Консьюмер переводов поставлен на паузу — лаг будет расти"
                    : "Консьюмер переводов работает, задержка "
                      + settings.getKafkaConsumerDelayMs() + " мс");
        }
        return REDIRECT;
    }

    @PostMapping("/rate-limit")
    public String rateLimit(@RequestParam(defaultValue = "false") boolean enabled,
                            @RequestParam(required = false) Integer rps,
                            RedirectAttributes redirect) {
        settings.applyRateLimit(enabled, rps);
        return done(redirect, enabled, "Ограничение частоты запросов");
    }

    @PostMapping("/reset")
    public String reset(RedirectAttributes redirect) {
        chaosService.resetAll();
        redirect.addFlashAttribute("success", "Все дефекты выключены, стенд в штатном режиме");
        return REDIRECT;
    }

    private String done(RedirectAttributes redirect, boolean enabled, String what) {
        redirect.addFlashAttribute("success", what + " — дефект "
                + (enabled ? "включён" : "выключен"));
        return REDIRECT;
    }
}
