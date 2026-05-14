package com.kumouri.kmodigipresbe.util;

import com.kumouri.kmodigipresbe.audit.AuditEvent;
import com.kumouri.kmodigipresbe.audit.AuditEventDTO;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.contact.Company;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.deal.Deal;
import com.kumouri.kmodigipresbe.model.request.ActivityDTO;
import com.kumouri.kmodigipresbe.model.request.CompanyDTO;
import com.kumouri.kmodigipresbe.model.request.ContactDTO;
import com.kumouri.kmodigipresbe.model.request.DealDTO;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationDTO;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * DTO &harr; entity boundary. Server-managed fields (tenantId, version, createdAt,
 * updatedAt, stageChangedAt) are never taken from the incoming DTO — they're populated
 * by the {@link com.kumouri.kmodigipresbe.tenancy.TenantStampingCallback}, Spring Data
 * auditing, or service layer.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface RequestMapper {

    // Email DTO <-> Request (existing pattern, retained)
    SingleEmailCommunicationDTO toSingleEmailCommunicationDTO(SingleEmailCommunicationRequest request);

    SingleEmailCommunicationRequest toSingleEmailCommunicationRequest(SingleEmailCommunicationDTO dto);

    default EmailContact toEmailContact(String email) {
        return email == null ? null : new EmailContact(email);
    }

    default String emailContactToString(EmailContact contact) {
        return contact == null ? null : contact.asString();
    }

    // Contact <-> ContactDTO
    @Mappings({
            @Mapping(target = "tenantId", ignore = true),
            @Mapping(target = "version", ignore = true),
            @Mapping(target = "createdAt", ignore = true),
            @Mapping(target = "updatedAt", ignore = true),
            @Mapping(target = "emails", source = "emails", qualifiedByName = "stringsToEmails")
    })
    Contact toContact(ContactDTO dto);

    @Mapping(target = "emails", source = "emails", qualifiedByName = "emailsToStrings")
    ContactDTO toContactDTO(Contact contact);

    @Named("stringsToEmails")
    default List<EmailContact> stringsToEmails(List<String> raw) {
        return raw == null ? List.of() : raw.stream().map(EmailContact::new).toList();
    }

    @Named("emailsToStrings")
    default List<String> emailsToStrings(List<EmailContact> parsed) {
        return parsed == null ? List.of() : parsed.stream().map(EmailContact::asString).toList();
    }

    // Company <-> CompanyDTO
    @Mappings({
            @Mapping(target = "tenantId", ignore = true),
            @Mapping(target = "version", ignore = true),
            @Mapping(target = "createdAt", ignore = true),
            @Mapping(target = "updatedAt", ignore = true)
    })
    Company toCompany(CompanyDTO dto);

    CompanyDTO toCompanyDTO(Company company);

    // Deal <-> DealDTO
    @Mappings({
            @Mapping(target = "tenantId", ignore = true),
            @Mapping(target = "version", ignore = true),
            @Mapping(target = "createdAt", ignore = true),
            @Mapping(target = "updatedAt", ignore = true),
            @Mapping(target = "stageChangedAt", ignore = true)
    })
    Deal toDeal(DealDTO dto);

    DealDTO toDealDTO(Deal deal);

    // Activity <-> ActivityDTO
    @Mappings({
            @Mapping(target = "tenantId", ignore = true),
            @Mapping(target = "version", ignore = true),
            @Mapping(target = "createdAt", ignore = true),
            @Mapping(target = "updatedAt", ignore = true)
    })
    Activity toActivity(ActivityDTO dto);

    ActivityDTO toActivityDTO(Activity activity);

    // AuditEvent -> AuditEventDTO (read-only — no inbound DTO)
    AuditEventDTO toAuditEventDTO(AuditEvent event);
}
