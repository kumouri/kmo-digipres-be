package com.kumouri.kmodigipresbe.service;

import com.kumouri.kmodigipresbe.config.JwtProperties;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.tenancy.JwtTenantResolver;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.Date;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtTokenService {

    private final JwtProperties props;
    private final SecretKeySpec key;

    public String mint(User user) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(props.issuer())
                    .subject(user.getId().toString())
                    .audience("kmosf-crm")
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(props.ttlSeconds())))
                    .claim(JwtTenantResolver.CLAIM_TENANT_ID, user.getTenantId().toString())
                    .claim(JwtTenantResolver.CLAIM_USER_ID, user.getId().toString())
                    .claim(JwtTenantResolver.CLAIM_ROLES, List.copyOf(user.getRoles()))
                    .claim("email", user.getEmail())
                    .build();
            SignedJWT signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            signed.sign(new MACSigner(key.getEncoded()));
            return signed.serialize();
        } catch (JOSEException ex) {
            throw new DigiPresBeException(ex, 1010, 500);
        }
    }
}
