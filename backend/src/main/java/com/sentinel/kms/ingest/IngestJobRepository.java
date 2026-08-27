package com.sentinel.kms.ingest;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IngestJobRepository extends JpaRepository<IngestJob, String> {

    /** Own jobs plus the shared seeded demo jobs (owner_sub null), newest first. */
    List<IngestJob> findByOwnerSubOrOwnerSubIsNullOrderByCreatedAtDesc(String ownerSub);

    List<IngestJob> findByStageNotIn(List<String> stages);
}
