package ru.ntbank.bank.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;
import ru.ntbank.bank.config.AppProperties;
import ru.ntbank.bank.domain.AppUser;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

/** Выпуск и разбор JWT для REST API. */
@Service
public class JwtService {

    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public JwtService(AppProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.jwt().secret().getBytes(StandardCharsets.UTF_8));
        this.accessTtl = Duration.ofMinutes(properties.jwt().accessTtlMinutes());
        this.refreshTtl = Duration.ofDays(properties.jwt().refreshTtlDays());
    }

    public String accessToken(AppUser user) {
        return build(user, TYPE_ACCESS, accessTtl);
    }

    public String refreshToken(AppUser user) {
        return build(user, TYPE_REFRESH, refreshTtl);
    }

    public long accessTtlSeconds() {
        return accessTtl.toSeconds();
    }

    private String build(AppUser user, String type, Duration ttl) {
        Date now = new Date();
        return Jwts.builder()
                .subject(user.getLogin())
                .id(java.util.UUID.randomUUID().toString())
                .claim("uid", user.getId())
                .claim("name", user.getFullName())
                .claim("phone", user.getPhone())
                .claim("role", user.getRole())
                .claim("typ", type)
                .issuer("nt-bank")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttl.toMillis()))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isRefresh(Claims claims) {
        return TYPE_REFRESH.equals(claims.get("typ", String.class));
    }

    public boolean isAccess(Claims claims) {
        return TYPE_ACCESS.equals(claims.get("typ", String.class));
    }

    public AppUserDetails toPrincipal(Claims claims) {
        return new AppUserDetails(
                claims.get("uid", Number.class).longValue(),
                claims.getSubject(),
                "",
                claims.get("name", String.class),
                claims.get("phone", String.class),
                claims.get("role", String.class),
                true);
    }
}
