package com.sentinel.kms.assistant;

import com.sentinel.kms.common.EvidenceReference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One turn in a {@link Conversation}. {@code role} is {@code user} or
 * {@code assistant}; assistant turns carry the grounded {@code citations}
 * and an optional analyst {@code rating} (feedback: 1 helpful, -1 not).
 */
@Entity
@Table(name = "messages")
@Getter
@Setter
@NoArgsConstructor
public class ChatMessage {

    @Id
    private String id; // uuid

    @Column(name = "conversation_id", nullable = false)
    private String conversationId;

    private String role; // user | assistant

    private String content;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "citations", columnDefinition = "text[]")
    private List<String> citations = new ArrayList<>();

    /**
     * Projection of immutable normalized citation rows. It is deliberately not a JSON column:
     * {@link MessageCitationReferenceStore} loads and writes the authoritative table.
     */
    @Transient
    private List<EvidenceReference> citationReferences = new ArrayList<>();

    /** Internal upload provenance used only to re-authorize transcript replay. */
    @Transient
    private List<SourceEvidenceReference> sourceEvidenceReferences = new ArrayList<>();

    /** 1 = helpful, -1 = not helpful, null = unrated. */
    private Integer rating;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
