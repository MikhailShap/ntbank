package ru.ntbank.bank.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import ru.ntbank.bank.domain.AppUser;

import java.util.Collection;
import java.util.List;

/** Принципал и для веб-сессии, и для JWT. Содержит id пользователя, чтобы не ходить лишний раз в базу. */
public class AppUserDetails implements UserDetails {

    private final Long id;
    private final String login;
    private final String password;
    private final String fullName;
    private final String phone;
    private final String role;
    private final boolean enabled;

    public AppUserDetails(Long id, String login, String password, String fullName,
                          String phone, String role, boolean enabled) {
        this.id = id;
        this.login = login;
        this.password = password;
        this.fullName = fullName;
        this.phone = phone;
        this.role = role;
        this.enabled = enabled;
    }

    public static AppUserDetails of(AppUser user) {
        return new AppUserDetails(user.getId(), user.getLogin(), user.getPasswordHash(),
                user.getFullName(), user.getPhone(), user.getRole(), user.isEnabled());
    }

    public Long getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public String getPhone() {
        return phone;
    }

    public String getRole() {
        return role;
    }

    /** Инициалы для аватарки в шапке. */
    public String getInitials() {
        String[] parts = fullName.trim().split("\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length && sb.length() < 2; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(Character.toUpperCase(parts[i].charAt(0)));
            }
        }
        return sb.toString();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return login;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
