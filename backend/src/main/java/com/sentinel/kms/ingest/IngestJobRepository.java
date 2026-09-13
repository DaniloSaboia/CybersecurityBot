package com.sentinel.kms.ingest;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface IngestJobRepository extends JpaRepository<IngestJob, String> {

    /**
     * Persisted-scope listing for an authenticated subject.  A null owner is
     * never an authority signal.  The database function is the canonical
     * governance decision, so direct job listings cannot drift from the
     * retrieval policy's persisted membership/scope/classification checks.
     */
    @Query(value = """
            select ij.*
            from ingest_jobs ij
            where ij.deleted_at is null
              and ij.cancelled_at is null
              and public.can_read_ingest_job(ij.id, :subject)
            order by ij.created_at desc
            """, nativeQuery = true)
    List<IngestJob> findVisibleJobs(@Param("subject") String subject);

    List<IngestJob> findByDemoTrueAndDeletedAtIsNullAndStageNotIn(List<String> stages);

    Optional<IngestJob> findByIdAndOwnerSubAndDeletedAtIsNull(String id, String ownerSub);
}
