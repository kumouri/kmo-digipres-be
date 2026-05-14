package com.kumouri.kmodigipresbe.service.imports;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.imports.ImportJob;
import com.kumouri.kmodigipresbe.model.imports.ImportJob.ImportError;
import com.kumouri.kmodigipresbe.model.imports.ImportJob.ImportStatus;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.ImportJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Contact CSV import. Synchronous-within-the-job for Phase 4 — the {@code POST
 * /imports/contacts} handler blocks on the full parse + persist before returning
 * the final {@link ImportJob}. Good enough for files up to a few thousand rows.
 * A future Quartz dispatch can take large imports off the request thread.
 *
 * <p>Idempotency: when a row has an {@code external_id} column, a re-import of
 * the same CSV updates the same {@code Contact} (matched by
 * {@code customFields.external_id}) rather than creating a duplicate. Rows
 * without {@code external_id} are always created.
 *
 * <p>Expected headers (case-insensitive): {@code external_id}, {@code first_name},
 * {@code last_name}, {@code email}, {@code phone}, {@code tags} (comma-separated).
 * Unknown columns are silently dropped.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsvImportService {

    private final ContactRepository contacts;
    private final ImportJobRepository jobs;

    public Mono<ImportJob> importContacts(Flux<DataBuffer> body) {
        return DataBufferUtils.join(body)
                .flatMap(buffer -> {
                    InputStream in = buffer.asInputStream(true);
                    return runImport(in);
                });
    }

    private Mono<ImportJob> runImport(InputStream in) {
        return Mono.fromCallable(() -> {
            ImportJob.ImportJobBuilder jobBuilder = ImportJob.builder()
                    .entityType("CONTACT")
                    .status(ImportStatus.RUNNING)
                    .startedAt(Instant.now());
            List<Contact> toUpsert = new ArrayList<>();
            List<ImportError> errors = new ArrayList<>();
            long totalRows = 0;
            long failedRows = 0;
            Set<String> seenExternalIds = new HashSet<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
                 CSVParser parser = CSVFormat.DEFAULT.builder()
                         .setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true)
                         .setIgnoreSurroundingSpaces(true).build().parse(reader)) {
                for (CSVRecord row : parser) {
                    totalRows++;
                    long rowNumber = row.getRecordNumber();
                    try {
                        String externalId = optional(row, "external_id");
                        if (externalId != null && !seenExternalIds.add(externalId)) {
                            errors.add(new ImportError(rowNumber,
                                    "duplicate external_id in same import", externalId));
                            failedRows++;
                            continue;
                        }
                        Contact c = buildContact(row, externalId);
                        toUpsert.add(c);
                    } catch (Exception ex) {
                        failedRows++;
                        errors.add(new ImportError(rowNumber, ex.getMessage(), null));
                    }
                }
            } catch (IOException ex) {
                throw new DigiPresBeException("CSV parse failed: " + ex.getMessage(),
                        1800, 400);
            }
            return ImportPlan.of(toUpsert, errors, totalRows, failedRows, jobBuilder);
        }).subscribeOn(Schedulers.boundedElastic()).flatMap(this::persistPlan);
    }

    private Mono<ImportJob> persistPlan(ImportPlan plan) {
        return Flux.fromIterable(plan.candidates())
                .concatMap(this::upsert)
                .reduce(new long[]{0L, 0L}, (acc, res) -> {
                    if (res == UpsertResult.CREATED || res == UpsertResult.UPDATED) acc[0]++;
                    else if (res == UpsertResult.SKIPPED) acc[1]++;
                    return acc;
                })
                .map(counts -> {
                    long succeeded = counts[0];
                    long skipped = counts[1];
                    long failed = plan.failedRows();
                    ImportStatus status = failed == 0
                            ? ImportStatus.SUCCEEDED
                            : (succeeded == 0 ? ImportStatus.FAILED : ImportStatus.PARTIAL);
                    return plan.builder()
                            .id(UUID.randomUUID())
                            .totalRows(plan.totalRows())
                            .succeededRows(succeeded)
                            .skippedRows(skipped)
                            .failedRows(failed)
                            .errors(plan.errors())
                            .status(status)
                            .completedAt(Instant.now())
                            .build();
                })
                .flatMap(jobs::save);
    }

    private Mono<UpsertResult> upsert(Contact candidate) {
        Object externalId = candidate.getCustomFields() == null
                ? null
                : candidate.getCustomFields().get("external_id");
        if (externalId == null) {
            return contacts.save(candidate).thenReturn(UpsertResult.CREATED);
        }
        // Match existing by external_id in the same tenant scope.
        return contacts.findAll()
                .filter(existing -> existing.getCustomFields() != null
                        && externalId.equals(existing.getCustomFields().get("external_id")))
                .next()
                .flatMap(existing -> {
                    if (candidate.getFirstName() != null) existing.setFirstName(candidate.getFirstName());
                    if (candidate.getLastName() != null) existing.setLastName(candidate.getLastName());
                    if (candidate.getEmails() != null && !candidate.getEmails().isEmpty()) {
                        existing.setEmails(candidate.getEmails());
                    }
                    if (candidate.getPhones() != null && !candidate.getPhones().isEmpty()) {
                        existing.setPhones(candidate.getPhones());
                    }
                    if (candidate.getTags() != null && !candidate.getTags().isEmpty()) {
                        existing.setTags(candidate.getTags());
                    }
                    return contacts.save(existing).thenReturn(UpsertResult.UPDATED);
                })
                .switchIfEmpty(contacts.save(candidate).thenReturn(UpsertResult.CREATED));
    }

    private Contact buildContact(CSVRecord row, String externalId) {
        String firstName = optional(row, "first_name");
        String lastName = optional(row, "last_name");
        String email = optional(row, "email");
        String phone = optional(row, "phone");
        String tags = optional(row, "tags");

        if (firstName == null && lastName == null && email == null) {
            throw new IllegalArgumentException(
                    "row needs at least one of first_name / last_name / email");
        }

        Contact.ContactBuilder b = Contact.builder()
                .type(ContactType.PERSON)
                .firstName(firstName)
                .lastName(lastName)
                .displayName(firstName == null && lastName == null
                        ? email
                        : (firstName == null ? lastName : firstName + (lastName == null ? "" : " " + lastName)));
        if (email != null) b.emails(List.of(new EmailContact(email)));
        if (phone != null) b.phones(List.of(
                new com.kumouri.kmodigipresbe.model.contact.PhoneNumber(phone, "primary")));
        if (tags != null) b.tags(new HashSet<>(Arrays.asList(tags.split(",\\s*"))));
        if (externalId != null) {
            b.customFields(java.util.Map.of("external_id", externalId));
        }
        return b.build();
    }

    private static String optional(CSVRecord row, String header) {
        if (!row.getParser().getHeaderNames().contains(header)) return null;
        String v = row.get(header);
        if (v == null) return null;
        String trimmed = v.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private enum UpsertResult { CREATED, UPDATED, SKIPPED }

    private record ImportPlan(List<Contact> candidates,
                              List<ImportError> errors,
                              long totalRows,
                              long failedRows,
                              ImportJob.ImportJobBuilder builder) {
        static ImportPlan of(List<Contact> candidates, List<ImportError> errors,
                             long totalRows, long failedRows,
                             ImportJob.ImportJobBuilder builder) {
            return new ImportPlan(candidates, errors, totalRows, failedRows, builder);
        }
    }
}
