package com.example.relay.user.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.relay.user.domain.User;


public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);
}
