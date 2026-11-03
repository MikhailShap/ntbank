package ru.ntbank.bank.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.ntbank.bank.domain.AppUser;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByLogin(String login);

    Optional<AppUser> findByPhone(String phone);

    boolean existsByLogin(String login);

    boolean existsByPhone(String phone);

    long countByRole(String role);
}
