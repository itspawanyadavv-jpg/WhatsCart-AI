package com.drc.whatsappbot.repository;

import com.drc.whatsappbot.model.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {
    Optional<ChatSession> findByPhone(String phone);
}

