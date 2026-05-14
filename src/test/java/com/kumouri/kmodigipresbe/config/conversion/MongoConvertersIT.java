package com.kumouri.kmodigipresbe.config.conversion;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.deal.Deal;
import com.kumouri.kmodigipresbe.model.deal.PipelineStage;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.DealRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the registered converters change Mongo's stored shape AND keep
 * round-trips lossless.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MongoConvertersIT {

    @Autowired DealRepository deals;
    @Autowired ContactRepository contacts;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private TenantContext ctx;

    @BeforeEach
    void setup() {
        mongo.remove(new Query(), Deal.class).block();
        mongo.remove(new Query(), Contact.class).block();
        tenantId = UUID.randomUUID();
        ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));
    }

    @Test
    void bigDecimalValue_storedAsDecimal128_andRoundTrips() {
        Deal saved = deals.save(Deal.builder()
                        .title("Acme")
                        .stage(PipelineStage.WON)
                        .value(new BigDecimal("12345.67"))
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(saved).isNotNull();

        // Raw doc: value must be Decimal128 (not String — the bug we're fixing).
        org.bson.Document raw = mongo.findOne(
                        new Query(Criteria.where("_id").is(saved.getId())),
                        org.bson.Document.class, "deals")
                .block();
        assertThat(raw).isNotNull();
        assertThat(raw.get("value")).isInstanceOf(Decimal128.class);
        assertThat(((Decimal128) raw.get("value")).bigDecimalValue())
                .isEqualByComparingTo(new BigDecimal("12345.67"));

        // Entity round-trip: loading via the repository yields the same BigDecimal.
        Deal loaded = deals.findById(saved.getId())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(loaded).isNotNull();
        assertThat(loaded.getValue()).isEqualByComparingTo(new BigDecimal("12345.67"));
    }

    @Test
    void internetAddress_storedAsFlatString_andRoundTripsViaEmailContact() {
        Contact saved = contacts.save(Contact.builder()
                        .type(ContactType.PERSON)
                        .firstName("Alice")
                        .emails(List.of(new EmailContact("alice@example.test")))
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(saved).isNotNull();

        // Raw doc: emails[0].email is the flat string — not the legacy nested doc.
        org.bson.Document raw = mongo.findOne(
                        new Query(Criteria.where("_id").is(saved.getId())),
                        org.bson.Document.class, "contacts")
                .block();
        assertThat(raw).isNotNull();
        @SuppressWarnings("unchecked")
        List<org.bson.Document> emails = (List<org.bson.Document>) raw.get("emails");
        assertThat(emails).hasSize(1);
        assertThat(emails.get(0).get("email")).isEqualTo("alice@example.test");

        // Entity round-trip: EmailContact reconstructs with a non-null email.
        Contact loaded = contacts.findById(saved.getId())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(loaded).isNotNull();
        assertThat(loaded.getEmails()).hasSize(1);
        assertThat(loaded.getEmails().get(0).asString()).isEqualTo("alice@example.test");
    }

    @Test
    void findByTenantAndEmailAddress_locatesContactStoredWithFlatPath() {
        contacts.save(Contact.builder()
                        .type(ContactType.PERSON).firstName("Bob")
                        .emails(List.of(new EmailContact("bob@example.test")))
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        List<Contact> found = contacts
                .findByTenantAndEmailAddress(tenantId, "bob@example.test")
                .collectList()
                .block();
        assertThat(found).isNotNull().hasSize(1);
        assertThat(found.get(0).getFirstName()).isEqualTo("Bob");
    }
}
