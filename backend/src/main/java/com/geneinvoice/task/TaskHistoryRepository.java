package com.geneinvoice.task;

import org.springframework.data.jpa.repository.JpaRepository;

/** The mirror's plain repository. Nothing reads a mirror through it yet (B3). */
public interface TaskHistoryRepository extends JpaRepository<TaskHistory, Long> {
}
