package com.sentinel.kms.ingest;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IngestJobRepository extends JpaRepository<IngestJob, String> {

    List<IngestJob> findAllByOrderByCreatedAtDesc();

    List<IngestJob> findByStageNotIn(List<String> stages);
}
