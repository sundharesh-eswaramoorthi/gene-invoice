package com.geneinvoice.history.types;

import com.geneinvoice.history.Rehydration;
import com.geneinvoice.history.TypeHistory;
import com.geneinvoice.history.versions.TaskVersion;
import com.geneinvoice.region.Region;
import com.geneinvoice.task.Task;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.geneinvoice.history.TypeHistory.sameInstant;

/** Tasks (D8): versioned like the records they are on. {@code updatedAt} is kept on a version but never makes one. */
public class TaskHistory implements TypeHistory<Task, TaskVersion> {

    public static final String CODE = "TASK";

    @Override public String code() { return CODE; }
    @Override public Class<Task> liveType() { return Task.class; }
    @Override public Class<TaskVersion> versionType() { return TaskVersion.class; }
    @Override public int rank() { return 70; }

    @Override
    public Map<Long, TaskVersion> snapshotAll(Collection<Task> live, SharedSessionContractImplementor s) {
        Map<Long, TaskVersion> versions = new LinkedHashMap<>();
        for (Task t : live) {
            TaskVersion v = new TaskVersion();
            v.setId(t.getId());
            v.setTitle(t.getTitle());
            v.setDescription(t.getDescription());
            v.setStatus(t.getStatus());
            v.setDueDate(t.getDueDate());
            v.setEntityType(t.getEntityType());
            v.setEntityId(t.getEntityId());
            v.setEntityLabel(t.getEntityLabel());
            v.setEntityDeleted(t.isEntityDeleted());
            v.setCustomerId(t.getCustomerId());
            v.setRegionId(Region.idOf(t.getRegion()));
            v.setTaskOrigin(t.getOrigin());
            v.setRuleId(t.getRuleId());
            v.setRunId(t.getRunId());
            v.setCreatedByUserId(t.getCreatedByUserId());
            v.setCompletedAt(t.getCompletedAt());
            v.setCompletedByUserId(t.getCompletedByUserId());
            v.setCancelledAt(t.getCancelledAt());
            v.setCreatedAt(t.getCreatedAt());
            v.setUpdatedAt(t.getUpdatedAt());
            versions.put(t.getId(), v);
        }
        return versions;
    }

    @Override
    public boolean sameState(TaskVersion a, TaskVersion b) {
        return Objects.equals(a.getTitle(), b.getTitle())
                && Objects.equals(a.getDescription(), b.getDescription())
                && a.getStatus() == b.getStatus()
                && Objects.equals(a.getDueDate(), b.getDueDate())
                && a.getEntityType() == b.getEntityType()
                && Objects.equals(a.getEntityId(), b.getEntityId())
                && Objects.equals(a.getEntityLabel(), b.getEntityLabel())
                && Objects.equals(a.getEntityDeleted(), b.getEntityDeleted())
                && Objects.equals(a.getCustomerId(), b.getCustomerId())
                && Objects.equals(a.getRegionId(), b.getRegionId())
                && Objects.equals(a.getTaskOrigin(), b.getTaskOrigin())
                && Objects.equals(a.getRuleId(), b.getRuleId())
                && Objects.equals(a.getRunId(), b.getRunId())
                && Objects.equals(a.getCreatedByUserId(), b.getCreatedByUserId())
                && sameInstant(a.getCompletedAt(), b.getCompletedAt())
                && Objects.equals(a.getCompletedByUserId(), b.getCompletedByUserId())
                && sameInstant(a.getCancelledAt(), b.getCancelledAt())
                && sameInstant(a.getCreatedAt(), b.getCreatedAt());
    }

    @Override
    public List<Task> rehydrate(List<TaskVersion> versions, Rehydration ctx) {
        Map<Long, Region> regions = ctx.regions(versions.stream().map(TaskVersion::getRegionId).toList());
        List<Task> tasks = new ArrayList<>(versions.size());
        for (TaskVersion v : versions) {
            HistoricalTask t = new HistoricalTask();
            t.setId(v.getId());
            t.setTitle(v.getTitle());
            t.setDescription(v.getDescription());
            t.setStatus(v.getStatus());
            t.setDueDate(v.getDueDate());
            t.setEntityType(v.getEntityType());
            t.setEntityId(v.getEntityId());
            t.setEntityLabel(v.getEntityLabel());
            t.setEntityDeleted(Boolean.TRUE.equals(v.getEntityDeleted()));
            t.setCustomerId(v.getCustomerId());
            t.setRegion(v.getRegionId() == null ? null : regions.get(v.getRegionId()));
            t.setOrigin(v.getTaskOrigin());
            t.setRuleId(v.getRuleId());
            t.setRunId(v.getRunId());
            t.setCreatedByUserId(v.getCreatedByUserId());
            t.setCompletedAt(v.getCompletedAt());
            t.setCompletedByUserId(v.getCompletedByUserId());
            t.setCancelledAt(v.getCancelledAt());
            t.setCreatedAt(v.getCreatedAt());
            t.setUpdatedAt(v.getUpdatedAt());
            tasks.add(t);
        }
        return tasks;
    }
}
