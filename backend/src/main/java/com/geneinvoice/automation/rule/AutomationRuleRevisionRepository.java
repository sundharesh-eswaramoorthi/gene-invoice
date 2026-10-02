package com.geneinvoice.automation.rule;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AutomationRuleRevisionRepository extends JpaRepository<AutomationRuleRevision, Long> {

    /** Each rule's latest revision saved by the point: the rules as they stood then. */
    @Transactional(readOnly = true)
    @Query("""
            select r from AutomationRuleRevision r
             where r.id = (select max(r2.id) from AutomationRuleRevision r2
                            where r2.ruleId = r.ruleId and r2.savedAt <= :at)
             order by r.ruleId
            """)
    List<AutomationRuleRevision> latestAt(@Param("at") Instant at);

    @Transactional(readOnly = true)
    Optional<AutomationRuleRevision> findFirstByRuleIdAndSavedAtLessThanEqualOrderByIdDesc(Long ruleId, Instant at);

    @Transactional(readOnly = true)
    List<AutomationRuleRevision> findByRuleIdOrderByIdAsc(Long ruleId);
}
