package com.sentinel.kms.ingest;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Persistence boundary for retained upload/import source material. */
public interface IngestSourceRepository extends JpaRepository<IngestSource, String> {

    Optional<IngestSource> findByJobId(String jobId);

    @Modifying
    @Transactional
    @Query("delete from IngestSource source where source.jobId = :jobId")
    int deleteByJobId(String jobId);
}
