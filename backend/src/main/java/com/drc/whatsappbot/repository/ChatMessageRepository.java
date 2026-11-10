package com.drc.whatsappbot.repository;

import com.drc.whatsappbot.model.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findByPhoneOrderByCreatedAtAscIdAsc(String phone);
    void deleteByPhone(String phone);
}

