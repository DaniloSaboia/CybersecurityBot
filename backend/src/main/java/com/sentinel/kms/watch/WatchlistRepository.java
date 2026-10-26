package com.sentinel.kms.watch;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WatchlistRepository extends JpaRepository<Watchlist, String> {

    List<Watchlist> findByOwnerSubOrderByCreatedAtDesc(String ownerSub);

    Optional<Watchlist> findByIdAndOwnerSub(String id, String ownerSub);
}
