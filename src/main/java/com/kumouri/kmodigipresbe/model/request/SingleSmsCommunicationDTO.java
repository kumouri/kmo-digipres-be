package com.kumouri.kmodigipresbe.model.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class SingleSmsCommunicationDTO {
    @NotBlank
    private String to;
    @NotBlank
    private String body;
}
