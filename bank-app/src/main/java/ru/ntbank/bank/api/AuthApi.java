package ru.ntbank.bank.api;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import ru.ntbank.bank.domain.AppUser;
import ru.ntbank.bank.exception.BusinessException;
import ru.ntbank.bank.repo.AppUserRepository;
import ru.ntbank.bank.security.AppUserDetails;
import ru.ntbank.bank.security.JwtService;

import java.time.LocalDateTime;

/** Вход в API и обновление токена. */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "1. Аутентификация", description = "JWT: access живёт 15 минут, refresh — 7 дней")
public class AuthApi {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthApi(AppUserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Вход по логину и паролю",
            description = "Тестовые клиенты: user0001 … user1000, пароль Passw0rd!")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        AppUser user = users.findByLogin(request.login())
                .filter(found -> passwordEncoder.matches(request.password(), found.getPasswordHash()))
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS",
                        "Неверный логин или пароль"));
        if (!user.isEnabled()) {
            throw BusinessException.forbidden("Пользователь заблокирован");
        }
        user.setLastLoginAt(LocalDateTime.now());
        users.save(user);
        return tokens(user);
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    @Operation(summary = "Обновление пары токенов по refresh-токену")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        Claims claims;
        try {
            claims = jwtService.parse(request.refreshToken());
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN",
                    "Refresh-токен недействителен или истёк");
        }
        if (!jwtService.isRefresh(claims)) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "WRONG_TOKEN_TYPE",
                    "Нужен refresh-токен");
        }
        AppUser user = users.findByLogin(claims.getSubject())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND",
                        "Пользователь не найден"));
        return tokens(user);
    }

    @GetMapping("/me")
    @Operation(summary = "Текущий пользователь по access-токену")
    public UserDto me(@AuthenticationPrincipal AppUserDetails principal) {
        return new UserDto(principal.getId(), principal.getUsername(), principal.getFullName(),
                principal.getPhone(), principal.getRole());
    }

    private TokenResponse tokens(AppUser user) {
        return new TokenResponse("Bearer",
                jwtService.accessToken(user),
                jwtService.refreshToken(user),
                jwtService.accessTtlSeconds(),
                new UserDto(user.getId(), user.getLogin(), user.getFullName(),
                        user.getPhone(), user.getRole()));
    }

    public record LoginRequest(@NotBlank(message = "Укажите логин") String login,
                               @NotBlank(message = "Укажите пароль") String password) {
    }

    public record RefreshRequest(@NotBlank(message = "Укажите refreshToken") String refreshToken) {
    }

    public record TokenResponse(String tokenType, String accessToken, String refreshToken,
                                long expiresIn, UserDto user) {
    }

    public record UserDto(Long id, String login, String fullName, String phone, String role) {
    }
}
