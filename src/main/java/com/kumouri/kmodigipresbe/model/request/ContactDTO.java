package com.kumouri.kmodigipresbe.model.request;

import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.contact.PostalAddress;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ContactDTO {
    private UUID id;
    private ContactType type;
    private String firstName;
    private String lastName;
    private String displayName;
    private UUID companyId;
    private List<String> emails;
    private List<PhoneNumber> phones;
    private List<PostalAddress> addresses;
    private Set<String> tags;
    private UUID ownerId;
    private Map<String, Object> customFields;
}
