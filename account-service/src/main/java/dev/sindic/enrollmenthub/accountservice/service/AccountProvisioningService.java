package dev.sindic.enrollmenthub.accountservice.service;

import dev.sindic.enrollmenthub.contracts.events.EnrollmentDecisionEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stub account service: records each decision once and logs it, provisioning nothing. It exists so
 * the decision-engine's outbound channel has a real consumer end to end.
 *
 * <p>Delivery is at-least-once, so the same {@code decisionId} can arrive more than once — a relay
 * re-dispatch after a lost confirm, or a broker redelivery (ADR-17). Duplicates are dropped against
 * a bounded in-memory set of recently seen ids. That holds for the lifetime of the process and the
 * last {@code capacity} decisions only; a provisioning implementation would record the
 * {@code decisionId} under a unique constraint in the same transaction that creates the account.
 *
 * <p>The log line carries the decision id, result and signal names only. The enrollment snapshot
 * holds personal data and is not logged.
 */
@Service
@Slf4j
public class AccountProvisioningService {

    private final Set<UUID> seenDecisionIds;

    AccountProvisioningService(AccountServiceProperties properties) {
        int capacity = properties.dedupCapacity();
        this.seenDecisionIds = Collections.newSetFromMap(Collections.synchronizedMap(
                new LinkedHashMap<>(16, 0.75f, false) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
                        return size() > capacity;
                    }
                }));
    }

    /**
     * Handles a decision unless its {@code decisionId} was already handled.
     *
     * @return {@code true} if handled, {@code false} if dropped as a duplicate
     */
    public boolean onDecision(EnrollmentDecisionEvent event) {
        if (!seenDecisionIds.add(event.decisionId())) {
            log.info("Duplicate enrollmentDecisionEvent dropped decisionId={}", event.decisionId());
            return false;
        }
        log.info("Received enrollmentDecisionEvent decisionId={} decision={} signals={} decidedAt={}",
                event.decisionId(), event.decisionResult(), event.signals().keySet(), event.decidedAt());
        return true;
    }
}
