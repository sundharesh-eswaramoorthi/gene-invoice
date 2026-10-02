package com.geneinvoice.document;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.query.ColumnDef;
import com.geneinvoice.common.query.ColumnType;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.common.scope.ViewScopeHolder;
import com.geneinvoice.document.DocumentDtos.DocumentDto;
import com.geneinvoice.document.DocumentTargets.Target;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    static final String NOT_FOUND = "Document not found";
    static final String PARENT_GONE = "Customer not found";

    private static final TableSchema RECORD_DOCUMENTS = TableSchema.of("documents", "uploadedAt,desc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).notFilterable().build(),
            ColumnDef.of("uploadedAt", "Uploaded", ColumnType.DATE).notFilterable().build());

    private final DocumentRepository repository;
    private final DocumentTargets targets;
    private final DocumentParentLock parentLock;
    private final DocumentRules rules;
    private final DocumentStorage storage;
    private final DocumentProperties properties;
    private final TableQueryExecutor queryExecutor;
    private final AuditService auditService;
    private final CurrentUser currentUser;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public record Download(String filename, long sizeBytes, InputStream stream) {}

    private record DocumentSnapshot(Long id, String filename, String contentType, long sizeBytes,
                                    DocumentVisibility visibility, String description,
                                    String uploadedBy) {}

    public DocumentDto upload(String entityType, Long entityId, MultipartFile file,
                              String description, String visibility) {
        DocumentEntityType type = DocumentEntityType.parse(entityType);
        Target target = targets.requireManageable(type, entityId);
        boolean customer = currentUser.isCustomer();
        DocumentVisibility wanted = customer ? DocumentVisibility.SHARED
                : visibility == null || visibility.isBlank()
                        ? DocumentVisibility.INTERNAL
                        : DocumentVisibility.parse(visibility);
        String note = rules.description(description);
        if (!storage.isConfigured()) {
            throw new DocumentStorageException(503, NoDocumentStorage.NOT_CONFIGURED);
        }

        User me = currentUser.require();
        try (DocumentRules.Staged staged = rules.stage(file)) {
            String key = key(type, entityId, staged.contentType());
            StoredFile stored = store(staged, key);
            Document saved;
            try {
                saved = transactions.execute(status -> {
                    // The record is read again here, under its lock, because it was last seen
                    // before the bytes were written and a large upload takes a while. A parent
                    // deleted in the meantime would leave this row pointing at nothing (DOC-5).
                    if (!parentLock.lockCustomer(target.customerId())) {
                        throw new NotFoundException(PARENT_GONE);
                    }
                    Document document = repository.save(Document.builder()
                            .entityType(type)
                            .entityId(target.id())
                            .entityLabel(target.label())
                            .customerId(target.customerId())
                            .filename(staged.filename())
                            .contentType(staged.contentType())
                            .sizeBytes(stored.sizeBytes())
                            .checksum(stored.checksum())
                            .storageKey(key)
                            .visibility(wanted)
                            .description(note)
                            .uploadedByUserId(me.getId())
                            .uploadedByName(nameOf(me))
                            .build());
                    auditService.record(type.name(), target.id(), "DOCUMENT_UPLOADED",
                            null, snapshot(document), me.getId(), null, null);
                    return document;
                });
            } catch (RuntimeException e) {
                storage.delete(key);
                throw e;
            }
            return toDto(saved, target);
        }
    }

    /** A record's documents, newest first; a customer login sees only the shared ones (D7). */
    @Transactional(readOnly = true)
    public PageResponse<DocumentDto> list(String entityType, Long entityId, Integer page, Integer size) {
        DocumentEntityType type = DocumentEntityType.parse(entityType);
        Target target = targets.requireVisible(type, entityId);
        TableQuery query = TableQuery.parse(RECORD_DOCUMENTS, page, size, null, List.of());
        var result = queryExecutor.run(Document.class, RECORD_DOCUMENTS, query,
                onRecord(type, entityId), List.of());
        return PageResponse.of(result.content().stream().map(d -> toDto(d, target)).toList(),
                query, result.total(), List.of());
    }

    @Transactional(readOnly = true)
    public DocumentDtos.DocumentCount count(String entityType, Long entityId) {
        DocumentEntityType type = DocumentEntityType.parse(entityType);
        targets.requireVisible(type, entityId);
        if (AsOf.active()) {
            TableQuery query = TableQuery.parse(RECORD_DOCUMENTS, null, null, null, List.of());
            return new DocumentDtos.DocumentCount(
                    queryExecutor.count(Document.class, RECORD_DOCUMENTS, query, onRecord(type, entityId)));
        }
        return new DocumentDtos.DocumentCount(currentUser.isCustomer()
                ? repository.countByEntityTypeAndEntityIdAndDeletedFalseAndVisibility(
                        type, entityId, DocumentVisibility.SHARED)
                : repository.countByEntityTypeAndEntityIdAndDeletedFalse(type, entityId));
    }

    @Transactional(readOnly = true)
    public Download download(Long id) {
        Document doc = visible(id);
        return new Download(doc.getFilename(), doc.getSizeBytes(), storage.open(doc.getStorageKey()));
    }

    @Transactional
    public DocumentDto update(Long id, DocumentDtos.PatchDocumentRequest req) {
        if (currentUser.isCustomer()) throw new AccessDeniedException("Not allowed");
        Document doc = live(id);
        Target target = targets.requireManageable(doc.getEntityType(), doc.getEntityId());
        DocumentSnapshot before = snapshot(doc);

        if (req.description() != null) doc.setDescription(rules.description(req.description()));
        if (req.visibility() != null) doc.setVisibility(DocumentVisibility.parse(req.visibility()));
        Document saved = repository.save(doc);

        auditService.record(doc.getEntityType().name(), target.id(), "DOCUMENT_UPDATED",
                before, snapshot(saved), currentUser.require().getId(), null, null);
        return toDto(saved, target);
    }

    @Transactional
    public void delete(Long id) {
        if (currentUser.isCustomer()) throw new AccessDeniedException("Not allowed");
        Document doc = liveForUpdate(id);
        Target target = targets.requireVisible(doc.getEntityType(), doc.getEntityId());
        ViewScopeHolder.requireLive();
        User me = currentUser.require();
        // In the record's region: the uploader may take back their own file where they may manage documents,
        // and anyone who may manage the record's documents there may remove any of them.
        boolean mayDeleteOwn = me.getId().equals(doc.getUploadedByUserId())
                && currentUser.hasIn(Privileges.DOCUMENT_MANAGE, target.regionId());
        if (!mayDeleteOwn && !targets.canManage(doc.getEntityType(), target.regionId())) {
            throw new AccessDeniedException("Not allowed");
        }
        DocumentSnapshot before = snapshot(doc);

        doc.setDeleted(true);
        doc.setDeletedByUserId(me.getId());
        doc.setDeletedAt(clock.instant());
        repository.save(doc);

        auditService.record(doc.getEntityType().name(), target.id(), "DOCUMENT_DELETED",
                before, null, me.getId(), null, null);
    }

    private Document visible(Long id) {
        Document doc = live(id);
        targets.requireVisible(doc.getEntityType(), doc.getEntityId());
        if (currentUser.isCustomer() && doc.getVisibility() != DocumentVisibility.SHARED) {
            throw new NotFoundException(NOT_FOUND);
        }
        return doc;
    }

    private Document live(Long id) {
        return notDeleted(repository.findById(id));
    }

    /** The same, held against anyone else changing it until this transaction ends (DOC-3). */
    private Document liveForUpdate(Long id) {
        return notDeleted(repository.findByIdForUpdate(id));
    }

    private static Document notDeleted(Optional<Document> found) {
        return found.filter(d -> !d.isDeleted())
                .orElseThrow(() -> new NotFoundException(NOT_FOUND));
    }

    /**
     * A record's documents; as of a date, those it had then (D8): uploaded by the end of that day and not yet
     * deleted, so a file uploaded later is left out and one deleted later is back.
     */
    private List<PredicateFactory> onRecord(DocumentEntityType type, Long entityId) {
        AsOfPoint point = AsOf.current();
        List<PredicateFactory> scope = new ArrayList<>(List.of(
                (root, q, cb) -> cb.equal(root.get("entityType"), type),
                (root, q, cb) -> cb.equal(root.get("entityId"), entityId)));
        if (point == null) {
            scope.add((root, q, cb) -> cb.isFalse(root.get("deleted")));
        } else {
            scope.add((root, q, cb) -> cb.lessThanOrEqualTo(root.<Instant>get("uploadedAt"), point.instant()));
            scope.add((root, q, cb) -> cb.or(cb.isFalse(root.get("deleted")),
                    cb.greaterThan(root.<Instant>get("deletedAt"), point.instant())));
        }
        if (currentUser.isCustomer()) {
            scope.add((root, q, cb) -> cb.equal(root.get("visibility"), DocumentVisibility.SHARED));
        }
        return scope;
    }

    /**
     * Edit and delete answer for the record's region, and are off in a historical view (D14). Download is a read,
     * served live (§5.2.3), so it stays on then; a file deleted since is listed then, but cannot be downloaded.
     */
    DocumentDto toDto(Document d, Target target) {
        boolean live = !AsOf.active();
        boolean manage = live && targets.canManage(d.getEntityType(), target.regionId());
        boolean uploader = live && !currentUser.isCustomer()
                && currentUser.hasIn(Privileges.DOCUMENT_MANAGE, target.regionId())
                && currentUser.require().getId().equals(d.getUploadedByUserId());
        return new DocumentDto(d.getId(), d.getEntityType(), d.getEntityId(), d.getEntityLabel(),
                d.getEntityType().link(d.getEntityId()), d.getFilename(), d.getContentType(),
                d.getSizeBytes(), DocumentDtos.sizeLabel(d.getSizeBytes()), d.getVisibility(),
                d.getDescription(),
                new DocumentDtos.UploadedBy(d.getUploadedByUserId(), d.getUploadedByName()),
                d.getUploadedAt(), !d.isDeleted(), manage, manage || uploader);
    }

    private static String key(DocumentEntityType type, Long entityId, String contentType) {
        return type.noun() + "/" + entityId + "/" + UUID.randomUUID()
                + "." + ContentSniffer.extensionOf(contentType);
    }

    private StoredFile store(DocumentRules.Staged staged, String key) {
        try (InputStream bytes = new BufferedInputStream(Files.newInputStream(staged.file()))) {
            return storage.put(bytes, key, staged.contentType(), properties.getMaxSizeBytes());
        } catch (IOException e) {
            throw new DocumentStorageException(500, DocumentRules.UNREADABLE, e);
        }
    }

    private static String nameOf(User u) {
        return u.getFullName() == null || u.getFullName().isBlank()
                ? u.getUsername()
                : u.getFullName().trim();
    }

    private static DocumentSnapshot snapshot(Document d) {
        return new DocumentSnapshot(d.getId(), d.getFilename(), d.getContentType(), d.getSizeBytes(),
                d.getVisibility(), d.getDescription(), d.getUploadedByName());
    }
}
