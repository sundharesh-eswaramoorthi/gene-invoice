package com.geneinvoice.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {

    /**
     * Every task on one account, for the cascade. Deliberately the ONLY finder on this repository
     * that is not keyed on the primary key: a finder that answers "everything" is how the next
     * endpoint written here would be unscoped without anybody deciding that it should be, so every
     * read surface goes through TableQueryExecutor instead (A6, B1).
     */
    List<Task> findByCustomerId(Long customerId);
}
