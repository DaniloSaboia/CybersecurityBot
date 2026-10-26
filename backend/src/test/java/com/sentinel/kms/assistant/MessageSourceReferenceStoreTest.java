package com.sentinel.kms.assistant;

import com.sentinel.kms.common.AccessDecision;
import com.sentinel.kms.kb.KnowledgeAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageSourceReferenceStoreTest {

    private static final SourceEvidenceReference SOURCE = new SourceEvidenceReference(
            "upload-1", "upload-revision-1", "upload-1:upload-revision-1");

    @Mock JdbcTemplate jdbc;
    @Mock KnowledgeAccessService access;

    @Test
    void writesOnlyCanonicalAuthorizedSourceIdentityWithADeterministicRowId() {
        when(access.canReadSource("workspace-member", SOURCE.sourceId(), SOURCE.sourceRevisionId(), SOURCE.sourceKey()))
                .thenReturn(allowed());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        MessageSourceReferenceStore store = new MessageSourceReferenceStore(jdbc, access);

        List<SourceEvidenceReference> persisted = store.bind("workspace-member", "message-1", List.of(SOURCE));

        assertThat(persisted).containsExactly(SOURCE);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), arguments.capture());
        assertThat(sql.getValue()).contains("insert into message_source_references");
        assertThat(arguments.getValue()).containsExactly(
                "source-reference:message-1:0", "message-1", 0,
                "upload-1", "upload-revision-1", "upload-1:upload-revision-1");
    }

    @Test
    void rejectsANonmemberOrMalformedSourceBeforeWriting() {
        when(access.canReadSource("nonmember", SOURCE.sourceId(), SOURCE.sourceRevisionId(), SOURCE.sourceKey()))
                .thenReturn(new AccessDecision(false, "SOURCE_MEMBERSHIP_REQUIRED", "shared", "internal", "approved"));
        MessageSourceReferenceStore store = new MessageSourceReferenceStore(jdbc, access);

        assertThatThrownBy(() -> store.bind("nonmember", "message-1", List.of(SOURCE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("readable live source");
        assertThatThrownBy(() -> store.bind("workspace-member", "message-1", List.of(
                new SourceEvidenceReference("upload-1", "upload-revision-1", "forged-key"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical source identity");

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void transcriptReadbackRestoresOnlyTheNormalizedSourceTuple() throws Exception {
        ResultSet row = org.mockito.Mockito.mock(ResultSet.class);
        when(row.getString("message_id")).thenReturn("message-1");
        when(row.getString("source_id")).thenReturn("upload-1");
        when(row.getString("source_revision_id")).thenReturn("upload-revision-1");
        when(row.getString("source_key")).thenReturn("upload-1:upload-revision-1");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<Object> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(row, 0));
        });
        MessageSourceReferenceStore store = new MessageSourceReferenceStore(jdbc, access);

        Map<String, List<SourceEvidenceReference>> references = store.loadByMessageIds(List.of("message-1"));

        assertThat(references).containsEntry("message-1", List.of(SOURCE));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).contains("message_source_references", "order by msr.message_id, msr.ordinal");
    }

    private static AccessDecision allowed() {
        return new AccessDecision(true, "SOURCE_WORKSPACE", "shared", "internal", "approved");
    }
}
