package com.sentinel.kms.watch;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KbAlertRepository extends JpaRepository<KbAlert, String> {

    List<KbAlert> findByOwnerSubOrderByCreatedAtDesc(String ownerSub, Pageable pageable);

    List<KbAlert> findByOwnerSubAndReadFalseOrderByCreatedAtDesc(String ownerSub, Pageable pageable);

    Optional<KbAlert> findByIdAndOwnerSub(String id, String ownerSub);

    Optional<KbAlert> findByWatchlistIdAndArticleId(String watchlistId, String articleId);

    long countByOwnerSubAndReadFalse(String ownerSub);
}
