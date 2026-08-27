package com.sentinel.kms.assistant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ConversationRepository extends JpaRepository<Conversation, String> {

    List<Conversation> findByUserSubOrderByUpdatedAtDesc(String userSub);

    /** Bump updated_at so the thread sorts by its latest activity, not creation. */
    @Transactional
    @Modifying
    @Query(value = "update conversations set updated_at = now() where id = :id", nativeQuery = true)
    void touch(String id);
}
