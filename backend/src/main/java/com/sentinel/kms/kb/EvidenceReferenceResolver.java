package com.sentinel.kms.kb;

import com.sentinel.kms.common.EvidenceReference;

import java.util.Optional;

/**
 * Stable assistant-facing bridge for server-resolved article evidence.
 * Implementations must fail closed and return only canonical current-approved
 * provenance that the supplied subject may read.
 */
public interface EvidenceReferenceResolver {

    Optional<EvidenceReference> resolveCurrentApproved(String subject, EvidenceReference reference);
}
