package com.kumouri.kmodigipresbe.config.conversion;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternetAddressConvertersTest {

    @Test
    void internetAddress_toString_roundTrip() {
        InternetAddress original;
        try {
            original = new InternetAddress("alice@example.test");
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        String encoded = InternetAddressConverters.InternetAddressToString.INSTANCE.convert(original);
        assertThat(encoded).isEqualTo("alice@example.test");
        InternetAddress decoded = InternetAddressConverters.StringToInternetAddress.INSTANCE
                .convert(encoded);
        assertThat(decoded).isNotNull();
        assertThat(decoded.getAddress()).isEqualTo("alice@example.test");
    }

    @Test
    void nullsAndBlanksPassThrough() {
        assertThat(InternetAddressConverters.InternetAddressToString.INSTANCE.convert(null)).isNull();
        assertThat(InternetAddressConverters.StringToInternetAddress.INSTANCE.convert(null)).isNull();
        assertThat(InternetAddressConverters.StringToInternetAddress.INSTANCE.convert("")).isNull();
        assertThat(InternetAddressConverters.StringToInternetAddress.INSTANCE.convert("  ")).isNull();
    }

    @Test
    void invalidString_propagatesEmailUtilException() {
        // Aligns with the rest of the codebase — EmailUtil.fromString throws
        // DigiPresBeException on a malformed address. Better to surface bad data
        // on read than to silently null it.
        assertThatThrownBy(() -> InternetAddressConverters.StringToInternetAddress.INSTANCE
                .convert("not a valid address @ at all"))
                .isInstanceOf(DigiPresBeException.class);
    }
}
