package org.gms.httpapi.controller;

import org.gms.data.entity.ApiRequestAudit;
import org.gms.data.entity.ToolExecutionAudit;
import org.gms.data.repo.ApiRequestAuditRepository;
import org.gms.data.repo.ToolExecutionAuditRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 管理审计接口的数量、记录和 limit 边界测试。 */
public final class AdminAuditControllerTest {

    @Test
    public void returnsRecentSafeAuditSnapshots() {
        ApiRequestAudit requestAudit = new ApiRequestAudit();
        requestAudit.setRequestId("req-1");
        ToolExecutionAudit toolAudit = new ToolExecutionAudit();
        toolAudit.setAuditRef("audit-1");
        RecordingApiAuditRepository apiRepository = new RecordingApiAuditRepository(requestAudit);
        RecordingToolAuditRepository toolRepository = new RecordingToolAuditRepository(toolAudit);
        AdminAuditController controller = new AdminAuditController(apiRepository, toolRepository);

        Map<String, Object> apiResult = controller.apiRequests(500);
        Map<String, Object> toolResult = controller.toolExecutions(0);

        assertThat(apiResult).containsEntry("total", 1L).containsEntry("limit", 200);
        assertThat(apiResult.get("records")).isEqualTo(List.of(requestAudit));
        assertThat(apiRepository.seenLimit).isEqualTo(200);
        assertThat(toolResult).containsEntry("total", 1L).containsEntry("limit", 50);
        assertThat(toolResult.get("records")).isEqualTo(List.of(toolAudit));
        assertThat(toolRepository.seenLimit).isEqualTo(50);
    }

    private static final class RecordingApiAuditRepository implements ApiRequestAuditRepository {

        private final ApiRequestAudit audit;
        private int seenLimit;

        private RecordingApiAuditRepository(ApiRequestAudit audit) {
            this.audit = audit;
        }

        @Override
        public void insert(ApiRequestAudit ignored) {
        }

        @Override
        public long count() {
            return 1;
        }

        @Override
        public List<ApiRequestAudit> findRecent(int limit) {
            seenLimit = limit;
            return List.of(audit);
        }
    }

    private static final class RecordingToolAuditRepository implements ToolExecutionAuditRepository {

        private final ToolExecutionAudit audit;
        private int seenLimit;

        private RecordingToolAuditRepository(ToolExecutionAudit audit) {
            this.audit = audit;
        }

        @Override
        public void insert(ToolExecutionAudit ignored) {
        }

        @Override
        public Optional<ToolExecutionAudit> findByAuditRef(String ignored) {
            return Optional.empty();
        }

        @Override
        public long count() {
            return 1;
        }

        @Override
        public List<ToolExecutionAudit> findRecent(int limit) {
            seenLimit = limit;
            return List.of(audit);
        }
    }
}
