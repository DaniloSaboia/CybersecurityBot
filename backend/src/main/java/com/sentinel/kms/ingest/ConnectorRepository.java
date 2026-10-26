package com.sentinel.kms.ingest;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConnectorRepository extends JpaRepository<Connector, String> {

    List<Connector> findAllByOrderByCreatedAtAsc();
}
