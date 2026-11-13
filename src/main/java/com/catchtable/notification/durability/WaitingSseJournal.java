package com.catchtable.notification.durability;

import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationMessage;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.notification.repository.SseEmitterRepository;
import com.catchtable.notification.service.NotificationSettingService;
import com.catchtable.waiting.dto.WaitingPositionPayload;
import com.catchtable.waiting.dto.WaitingQueueChangedEvent;
import com.catchtable.waiting.entity.WaitingStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.event.EventListener;
import com.catchtable.notification.repository.WaitingSseJournalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Durable steps deliberately return only after their own transaction has committed. */
@Service
public class WaitingSseJournal {
    public record Root(UUID id, String kind, Long waitingId, Integer callCount, Long storeId,
                       LocalDate date, Long memberId, String type, String message, boolean expanded) {}
    public record Claim(Root root, UUID token) {}
    public record Target(UUID id, UUID rootId, Long memberId, Long waitingId, Integer callCount,
                         String generation, String type, String message) {}
    public record Attempt(UUID id, Target target, UUID token) {}
    public record Result(UUID attemptId, String outcome, OffsetDateTime observedAt) {}

    private final WaitingSseJournalRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final WaitingSseProperties properties;
    private final NotificationSettingService settings;
    private final SseEmitterRepository connections;
    private final TransactionTemplate tx;

    public WaitingSseJournal(WaitingSseJournalRepository repository, ObjectMapper mapper, Clock clock,
                             WaitingSseProperties properties, NotificationSettingService settings,
                             SseEmitterRepository connections, PlatformTransactionManager manager) {
        this.repository = repository; this.mapper = mapper; this.clock = clock; this.properties = properties;
        this.settings = settings; this.connections = connections;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(10);
    }

    public static boolean isWaiting(CustomerNotificationEvent event) {
        return event.type().name().startsWith("WAITING_");
    }

    @EventListener
    public void capture(CustomerNotificationEvent event) {
        if (isWaiting(event)) enqueue(event);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(CustomerNotificationEvent event) {
        if (!isWaiting(event)) throw new IllegalArgumentException("Only waiting notifications");
        requireBusinessTransaction();
        var ref = event.waitingEvent();
        Long waiting = ref == null ? (event.type() == NotificationType.WAITING_CHANGED ? null : event.targetId()) : ref.waitingId();
        Integer count = ref == null ? null : ref.callCount();
        if (waiting != null && count == null) {
            count = repository.findCallCount(waiting);
        }
        Long store = event.type() == NotificationType.WAITING_CHANGED ? event.targetId() : null;
        if (store == null && waiting != null) {
            store = repository.findStoreId(waiting);
        }
        String key = (ref == null ? event.deliveryKey() : ref.eventKey()) + ":direct:" + event.type() + ":" + event.memberId() + ":" + event.targetId();
        insert(key, "DIRECT", waiting, count, ref == null ? null : ref.recallCount(),
                store, null, event.memberId(), event.type().name(), encode(NotificationMessage.from(event, LocalDateTime.now(clock))));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueQueue(WaitingQueueChangedEvent event) {
        requireBusinessTransaction();
        var ref = event.waitingEvent();
        insert((ref == null ? event.deliveryKey() : ref.eventKey()) + ":queue:" + event.storeId(), "QUEUE",
                ref == null ? null : ref.waitingId(), ref == null ? null : ref.callCount(),
                ref == null ? null : ref.recallCount(), event.storeId(), event.waitingDate(), null, null, null);
    }

    private void requireBusinessTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("Waiting SSE requires a writable business transaction");
    }

    private void insert(String key, String kind, Long waiting, Integer callCount, Integer recallCount,
                        Long store, LocalDate date, Long member, String type, String message) {
        // Serialize admissions, including duplicate listeners. This lock rolls back with the business TX.
        repository.lockCapacity();
        if (repository.rootExists(key)) return;
        if (!repository.reserveCapacity(properties.maxRetainedRoots())) throw new WaitingSseCapacityException();
        UUID id = UUID.randomUUID();
        repository.insertRoot(id,key,kind,waiting,callCount,recallCount,store,date,member,type,message);
        observe(id + ":accepted", id, null, null, "ACCEPTED", null, "BUSINESS_TX", now());
    }

    public Claim claim() {
        return tx.execute(s -> {
            var next = repository.lockNextRoot();
            if (next.isEmpty()) return null;
            Root root = root(next.get());
            // A previous MARKED attempt may or may not have entered send. It is NEVER reset to READY.
            for (var row : repository.markedAttempts(root.id())) {
                UUID attempt = row.id(), target = row.targetId();
                repository.markAttemptUnknown(attempt,"LEASE_EXPIRED_AFTER_MARK");
                repository.setTargetState(target,"UNKNOWN","LEASE_EXPIRED_AFTER_MARK");
                observe(attempt + ":unknown", root.id(),target,attempt,"UNKNOWN","LEASE_EXPIRED_AFTER_MARK","ATTEMPT_MARK_COMMITTED",now());
            }
            UUID token = UUID.randomUUID();
            repository.claimRoot(root.id(),token,properties.leaseSeconds());
            return new Claim(root,token);
        });
    }

    public boolean expand(Claim claim) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            if (!lockClaim(claim)) return false;
            if (repository.expanded(claim.root().id())) return true;
            Root r = claim.root();
            var targets = new ArrayList<Target>();
            int recipients;
            if (r.kind().equals("DIRECT")) {
                recipients = 1;
                addTargets(targets, r.id(), r.memberId(), r.waitingId(), r.callCount(), r.type(), r.message());
            } else {
                var queue = repository.activeQueue(r.storeId(),r.date(),properties.maxTargetsPerRoot()+1);
                recipients = queue.size();
                int position = 0;
                for (var row : queue) {
                    position++;
                    Long waitingId = row.id(), memberId = row.memberId();
                    var event = new CustomerNotificationEvent(memberId,NotificationType.WAITING_POSITION_CHANGED,waitingId,null,
                            new WaitingPositionPayload(waitingId,position,position-1,WaitingStatus.valueOf(row.status())));
                    addTargets(targets,r.id(),memberId,waitingId,row.callCount(),event.type().name(),
                            encode(NotificationMessage.from(event,LocalDateTime.now(clock))));
                    if (targets.size() > properties.maxTargetsPerRoot()) break;
                }
            }
            if (targets.size() > properties.maxTargetsPerRoot()) {
                observe(r.id()+":target-limit",r.id(),null,null,"EXPANSION_BLOCKED","TARGET_LIMIT","ROOT_COMMITTED",now());
                return false;
            }
            for (Target target : targets) {
                String reason = skipReason(target);
                if (reason == null && target.generation().equals("NONE")) reason = "NO_CONNECTION";
                repository.insertTarget(targetRow(target),reason == null ? "READY" : "SKIPPED",reason);
                if (reason != null) observe(target.id()+":skip",r.id(),target.id(),null,"SKIPPED",reason,"TARGET_SNAPSHOT",now());
            }
            repository.completeExpansion(r.id(),recipients,targets.size(),now());
            observe(r.id()+":expanded",r.id(),null,null,"EXPANDED",recipients==0 ? "ZERO_RECIPIENTS" : null,"TARGET_SNAPSHOT",now());
            return true;
        }));
    }

    private void addTargets(List<Target> targets, UUID root, Long member, Long waiting, Integer count, String type, String message) {
        var active = connections.connections(member);
        if (active.isEmpty()) targets.add(new Target(UUID.randomUUID(),root,member,waiting,count,"NONE",type,message));
        for (var connection : active) {
            targets.add(new Target(UUID.randomUUID(),root,member,waiting,count,connection.generation(),type,message));
            if (targets.size()>properties.maxTargetsPerRoot()) break;
        }
    }

    public List<Target> ready(Claim claim) {
        return tx.execute(s -> repository.readyTargets(claim.root().id()).stream().map(WaitingSseJournal::target).toList());
    }

    /** Commit failure here propagates to the worker: no network send is permitted. */
    public Attempt mark(Claim claim, Target target) {
        if (!claim.root().id().equals(target.rootId())) throw new IllegalArgumentException("Target belongs to another root");
        return tx.execute(s -> {
            if (!lockClaim(claim)) return null;
            if (!"READY".equals(repository.lockTargetStatus(target.id()))) return null;
            String reason = skipReason(target);
            if (reason == null && connections.find(target.memberId(),target.generation()) == null) reason="CONNECTION_GONE";
            if (reason != null) {
                repository.setTargetState(target.id(),"SKIPPED",reason);
                observe(target.id()+":skip",target.rootId(),target.id(),null,"SKIPPED",reason,"TARGET_SNAPSHOT",now());
                return null;
            }
            UUID id = UUID.randomUUID();
            repository.insertAttempt(id,target.id(),claim.token());
            // Renew only while holding the root lock, never after ownership is lost.
            repository.renewLease(target.rootId(),properties.leaseSeconds());
            observe(id+":marked",target.rootId(),target.id(),id,"MARKED",null,"ATTEMPT_MARK_COMMITTED",now());
            return new Attempt(id,target,claim.token());
        });
    }

    public boolean authorized(Attempt attempt) {
        return Boolean.TRUE.equals(tx.execute(s -> repository.authorized(attempt.id(),attempt.token())));
    }

    /** Idempotent persistence only: this method never touches a network connection. Late results are accepted. */
    public void saveResult(Result result) {
        tx.executeWithoutResult(s -> {
            UUID root = repository.rootOfAttempt(result.attemptId());
            repository.lockRoot(root);
            var attempt = repository.lockAttempt(result.attemptId());
            String old = attempt.status();
            if (!old.equals("MARKED") && !old.equals("UNKNOWN")) {
                if (!old.equals(result.outcome())) throw new IllegalStateException("Conflicting result for an existing attempt");
                return;
            }
            String targetStatus = switch (result.outcome()) {
                case "SEND_RETURNED" -> "SENT";
                case "IO_ERROR", "ILLEGAL_STATE" -> "FAILED";
                case "RUNTIME_ERROR" -> "UNKNOWN";
                case "CONNECTION_GONE" -> "SKIPPED";
                default -> throw new IllegalArgumentException("Unsupported observation");
            };
            UUID target = attempt.targetId();
            repository.saveAttemptResult(result.attemptId(),result.outcome(),result.observedAt());
            repository.setTargetState(target,targetStatus,result.outcome());
            observe(result.attemptId()+":result",root,target,result.attemptId(),result.outcome(),result.outcome(),"SERVER_OBSERVATION",result.observedAt());
            finishLocked(root);
        });
    }

    public void finish(Claim claim) {
        tx.executeWithoutResult(s -> { if (lockClaim(claim)) finishLocked(claim.root().id()); });
    }

    private void finishLocked(UUID root) {
        if (!repository.expanded(root) || repository.unfinishedTargetCount(root) != 0) return;
        boolean unknown = repository.unknownTargetCount(root)>0;
        repository.finishRoot(root,unknown ? "UNKNOWN" : "DONE",now());
    }

    private boolean lockClaim(Claim claim) {
        return repository.lockClaim(claim.root().id(),claim.token());
    }

    private String skipReason(Target target) {
        if (!settings.allows(target.memberId(),NotificationType.valueOf(target.type()))) return "SETTING_DISABLED";
        if (target.type().equals("WAITING_CALLED") || target.type().equals("WAITING_POSITION_CHANGED")) {
            var current = repository.waitingState(target.waitingId());
            if (current.isEmpty()) return "WAITING_MISSING";
            var row = current.get();
            if (target.type().equals("WAITING_CALLED")) {
                if (target.callCount()!=null && target.callCount()!=row.callCount()) return "SUPERSEDED_CALL";
                if (!List.of("CALLED","CONFIRMED").contains(row.status())) return "WAITING_INACTIVE";
                var expires = repository.callExpiry(target.waitingId());
                if (expires == null || !expires.isAfter(now())) return "CALL_EXPIRED";
            } else if (!List.of("WAITING","CALLED","CONFIRMED").contains(row.status())) return "WAITING_INACTIVE";
        }
        return null;
    }

    private void observe(String key, UUID root, UUID target, UUID attempt, String phase, String reason, String stage, OffsetDateTime at) {
        repository.observe(key,root,target,attempt,phase,reason,stage,at);
    }
    private String encode(Object message) {
        try {
            String json = mapper.writeValueAsString(message);
            if (json.getBytes(StandardCharsets.UTF_8).length > properties.maxMessageBytes()) throw new WaitingSseCapacityException();
            return json;
        } catch (JsonProcessingException e) { throw new IllegalArgumentException("Cannot encode waiting SSE",e); }
    }
    private OffsetDateTime now() { return OffsetDateTime.now(clock); }
    private static Root root(WaitingSseJournalRepository.RootRow r) {
        return new Root(r.id(),r.kind(),r.waitingId(),r.callCount(),r.storeId(),r.date(),r.memberId(),r.type(),r.message(),r.expanded());
    }
    private static Target target(WaitingSseJournalRepository.TargetRow r) {
        return new Target(r.id(),r.rootId(),r.memberId(),r.waitingId(),r.callCount(),r.generation(),r.type(),r.message());
    }
    private static WaitingSseJournalRepository.TargetRow targetRow(Target t) {
        return new WaitingSseJournalRepository.TargetRow(t.id(),t.rootId(),t.memberId(),t.waitingId(),t.callCount(),t.generation(),t.type(),t.message());
    }
}
