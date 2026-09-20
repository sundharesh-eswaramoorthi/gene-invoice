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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Attaching files to records, and reading them back (§4). Nothing here is one transaction end to
 * end: the upload is checked and stored with no transaction open, and only the row is written in
 * one, so a database connection is never held while bytes are moving.
 *
 * <p>The order a failure leaves things in is the point of AC-C18. Bytes are stored before the row
 * is written and removed again when writing it fails, so a row never points at bytes that are not
 * there, and bytes without a row are unreachable by any endpoint.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    static final String NOT_FOUND = "Document not found";
    /** What an upload says when the record it was landing on went while it was being stored. */
    static final String PARENT_GONE = "Customer not found";

    /** A record's documents page like any list, newest first; there is nothing to filter or re-sort. */
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

    /** The bytes on their way out, and what the browser should call them. */
    public record Download(String filename, long sizeBytes, InputStream stream) {}

    /** What an upload, an edit and a delete are written to the audit log as. */
    private record DocumentSnapshot(Long id, String filename, String contentType, long sizeBytes,
                                    DocumentVisibility visibility, String description,
                                    String uploadedBy) {}

    // ---- uploading ---------------------------------------------------------------------

    /**
     * Attaches a file to a record the caller may change. A customer login's upload lands
     * {@code SHARED} whatever it asks for: an internal document would be invisible to the very
     * person who uploaded it (§1, answer 4).
     */
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
                // A transaction that threw did not write the row, so nothing can reach these
                // bytes: take them back out. The guard stops at the transaction — past here the
                // document is real, and a failure building the answer is a 500 over a whole
                // document rather than a reason to take its file away from it (AC-C18).
                storage.delete(key);
                throw e;
            }
            return toDto(saved);
        }
    }

    // ---- reading -----------------------------------------------------------------------

    /** A record's documents, newest first; a customer login sees only the shared ones (D7). */
    @Transactional(readOnly = true)
    public PageResponse<DocumentDto> list(String entityType, Long entityId, Integer page, Integer size) {
        DocumentEntityType type = DocumentEntityType.parse(entityType);
        targets.requireVisible(type, entityId);
        TableQuery query = TableQuery.parse(RECORD_DOCUMENTS, page, size, null, List.of());
        var result = queryExecutor.run(Document.class, RECORD_DOCUMENTS, query,
                onRecord(type, entityId), List.of());
        return PageResponse.of(result.content().stream().map(this::toDto).toList(),
                query, result.total(), List.of());
    }

    /** How many there are, for the tab's badge — counted the same way the list is filtered. */
    @Transactional(readOnly = true)
    public DocumentDtos.DocumentCount count(String entityType, Long entityId) {
        DocumentEntityType type = DocumentEntityType.parse(entityType);
        targets.requireVisible(type, entityId);
        return new DocumentDtos.DocumentCount(currentUser.isCustomer()
                ? repository.countByEntityTypeAndEntityIdAndDeletedFalseAndVisibility(
                        type, entityId, DocumentVisibility.SHARED)
                : repository.countByEntityTypeAndEntityIdAndDeletedFalse(type, entityId));
    }

    /**
     * The bytes, for a caller allowed to have them. Authorised on this request and every request,
     * with no URL that outlives the check (D8); the stream is opened here and handed straight to
     * the response, so no file is ever read into memory.
     */
    @Transactional(readOnly = true)
    public Download download(Long id) {
        Document doc = visible(id);
        return new Download(doc.getFilename(), doc.getSizeBytes(), storage.open(doc.getStorageKey()));
    }

    // ---- changing ----------------------------------------------------------------------

    /** The description and the visibility; never a customer login, which cannot share or unshare. */
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
        return toDto(saved);
    }

    /**
     * Soft-deletes it (AC-C3): whoever may change the record, and whoever uploaded it, and never a
     * customer login. The row and the bytes are both retained — the row for the audit trail, the
     * bytes because nothing reaches them once the row is deleted (§1, answer 8).
     *
     * <p>The row is read under its write lock, so a second delete of the same document waits for
     * this one and then finds it already deleted. Deleting a document twice at once is one delete
     * and one 404, exactly as deleting it twice in a row is (DOC-3).
     */
    @Transactional
    public void delete(Long id) {
        if (currentUser.isCustomer()) throw new AccessDeniedException("Not allowed");
        Document doc = liveForUpdate(id);
        Target target = targets.requireVisible(doc.getEntityType(), doc.getEntityId());
        User me = currentUser.require();
        if (!me.getId().equals(doc.getUploadedByUserId()) && !targets.canManage(doc.getEntityType())) {
            throw new AccessDeniedException("Not allowed");
        }
        DocumentSnapshot before = snapshot(doc);

        doc.setDeleted(true);
        doc.setDeletedByUserId(me.getId());
        doc.setDeletedAt(Instant.now());
        repository.save(doc);

        auditService.record(doc.getEntityType().name(), target.id(), "DOCUMENT_DELETED",
                before, null, me.getId(), null, null);
    }

    // ---- what the caller may see -------------------------------------------------------

    /** A live document the caller may read: the record's own answer, then visibility (§4.5). */
    private Document visible(Long id) {
        Document doc = live(id);
        targets.requireVisible(doc.getEntityType(), doc.getEntityId());
        if (currentUser.isCustomer() && doc.getVisibility() != DocumentVisibility.SHARED) {
            throw new NotFoundException(NOT_FOUND);
        }
        return doc;
    }

    /** A document that has not been deleted; a deleted one is gone as far as any endpoint knows. */
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

    /** The documents on one record that this caller may see. */
    private List<PredicateFactory> onRecord(DocumentEntityType type, Long entityId) {
        List<PredicateFactory> scope = new ArrayList<>(List.of(
                (root, q, cb) -> cb.equal(root.get("entityType"), type),
                (root, q, cb) -> cb.equal(root.get("entityId"), entityId),
                (root, q, cb) -> cb.isFalse(root.get("deleted"))));
        if (currentUser.isCustomer()) {
            scope.add((root, q, cb) -> cb.equal(root.get("visibility"), DocumentVisibility.SHARED));
        }
        return scope;
    }

    DocumentDto toDto(Document d) {
        boolean manage = targets.canManage(d.getEntityType());
        boolean uploader = !currentUser.isCustomer()
                && currentUser.has(Privileges.DOCUMENT_MANAGE)
                && currentUser.require().getId().equals(d.getUploadedByUserId());
        return new DocumentDto(d.getId(), d.getEntityType(), d.getEntityId(), d.getEntityLabel(),
                d.getEntityType().link(d.getEntityId()), d.getFilename(), d.getContentType(),
                d.getSizeBytes(), DocumentDtos.sizeLabel(d.getSizeBytes()), d.getVisibility(),
                d.getDescription(),
                new DocumentDtos.UploadedBy(d.getUploadedByUserId(), d.getUploadedByName()),
                d.getUploadedAt(), true, manage, manage || uploader);
    }

    // ---- storage keys ------------------------------------------------------------------

    /**
     * Where the bytes go: the kind of record, its id, and a name nobody can guess or collide with,
     * with the extension of what the file turned out to be. Nothing the uploader sent is in it, so
     * no name can reach outside the storage root (AC-C8).
     */
    private static String key(DocumentEntityType type, Long entityId, String contentType) {
        return type.noun() + "/" + entityId + "/" + UUID.randomUUID()
                + "." + ContentSniffer.extensionOf(contentType);
    }

    /** Hands the staged bytes to storage, and closes the read of them whatever happens. */
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
