package com.kumouri.kmodigipresbe.controller.portal;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.service.portal.WebAuthnService;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * WebAuthn / passkey endpoints. The webauthn4j-driven verification path is wired
 * but the actual cryptographic verification is left as a follow-up — see
 * {@link WebAuthnService}'s class-level note. Calls to the {@code finish} endpoints
 * return 501 until the verification TODOs are resolved; the {@code start} endpoints
 * already issue real challenges so an FE can be developed against them.
 */
@RestController
@RequestMapping("/portal/auth/passkey")
@RequiredArgsConstructor
public class PortalPasskeyController {

    private final WebAuthnService webAuthnService;

    @PostMapping("/register/start")
    public Mono<WebAuthnService.RegistrationChallenge> registerStart() {
        return TenantContextHolder.required().flatMap(ctx -> {
            if (ctx.userId() == null) {
                return Mono.error(new DigiPresBeException(
                        "Passkey registration requires authentication", 1280, 401));
            }
            return webAuthnService.startRegistration(ctx.userId());
        });
    }

    @PostMapping("/register/finish")
    public Mono<WebAuthnService.RegisteredCredential> registerFinish(
            @Valid @RequestBody RegisterFinish req) {
        return TenantContextHolder.required().flatMap(ctx -> {
            if (ctx.userId() == null) {
                return Mono.error(new DigiPresBeException(
                        "Passkey registration requires authentication", 1281, 401));
            }
            return webAuthnService.finishRegistration(
                    ctx.userId(),
                    req.ticket(),
                    req.credentialId(),
                    decode(req.attestationObject()),
                    decode(req.clientDataJson()),
                    req.displayName(),
                    req.transports());
        });
    }

    @PostMapping("/login/start")
    public Mono<WebAuthnService.AssertionChallenge> loginStart(@RequestBody LoginStart req) {
        return webAuthnService.startAssertion(req.email());
    }

    @PostMapping("/login/finish")
    public Mono<Void> loginFinish(@Valid @RequestBody LoginFinish req) {
        return webAuthnService.finishAssertion(
                        req.ticket(),
                        req.credentialId(),
                        decode(req.authenticatorData()),
                        decode(req.clientDataJson()),
                        decode(req.signature()))
                .then();
    }

    private static byte[] decode(String base64Url) {
        if (base64Url == null) return new byte[0];
        return java.util.Base64.getUrlDecoder().decode(base64Url);
    }

    public record RegisterFinish(@NotBlank String ticket,
                                 @NotBlank String credentialId,
                                 @NotBlank String attestationObject,
                                 @NotBlank String clientDataJson,
                                 String displayName,
                                 List<String> transports) {}

    public record LoginStart(String email) {}

    public record LoginFinish(@NotBlank String ticket,
                              @NotBlank String credentialId,
                              @NotBlank String authenticatorData,
                              @NotBlank String clientDataJson,
                              @NotBlank String signature) {}
}
