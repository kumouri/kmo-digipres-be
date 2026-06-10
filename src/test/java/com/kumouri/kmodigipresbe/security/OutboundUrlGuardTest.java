package com.kumouri.kmodigipresbe.security;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Security fix BE-08/BE-09/BE-16 — {@link OutboundUrlGuard} unit coverage against the
 * PRODUCTION-strict policy (HTTPS required, all internal ranges blocked). Block cases use
 * literal-IP URLs so {@code InetAddress.getAllByName} resolves the literal with no network
 * call; the allow case uses a literal public IP for the same reason (no DNS flakiness).
 */
class OutboundUrlGuardTest {

    private OutboundUrlGuard strictGuard() {
        OutboundUrlGuardProperties props = new OutboundUrlGuardProperties();
        // production defaults: requireHttps=true, allowPrivateNetworks=false
        return new OutboundUrlGuard(props);
    }

    // ── scheme policy ────────────────────────────────────────────────────────────

    @Test
    void rejectsHttpWhenHttpsRequired() {
        assertThatThrownBy(() -> strictGuard().validateSync("http://93.184.216.34/x"))
                .isInstanceOf(DigiPresBeException.class)
                .satisfies(e -> assertThat(((DigiPresBeException) e).getErrorCode())
                        .isEqualTo(OutboundUrlGuard.ERROR_CODE));
    }

    @Test
    void rejectsNonHttpScheme() {
        assertThatThrownBy(() -> strictGuard().validateSync("ftp://93.184.216.34/x"))
                .isInstanceOf(DigiPresBeException.class);
        assertThatThrownBy(() -> strictGuard().validateSync("file:///etc/passwd"))
                .isInstanceOf(DigiPresBeException.class);
    }

    @Test
    void rejectsBlankAndMalformed() {
        assertThatThrownBy(() -> strictGuard().validateSync(""))
                .isInstanceOf(DigiPresBeException.class);
        assertThatThrownBy(() -> strictGuard().validateSync("not a url"))
                .isInstanceOf(DigiPresBeException.class);
        assertThatThrownBy(() -> strictGuard().validateSync("https://"))
                .isInstanceOf(DigiPresBeException.class);
    }

    // ── internal-range blocking (the SSRF core) ──────────────────────────────────

    @Test
    void blocksCloudMetadataAddress() {
        assertThatThrownBy(() -> strictGuard().validateSync("https://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(DigiPresBeException.class)
                .hasMessageContaining("internal");
    }

    @Test
    void blocksLoopback() {
        assertThatThrownBy(() -> strictGuard().validateSync("https://127.0.0.1/admin"))
                .isInstanceOf(DigiPresBeException.class);
    }

    @Test
    void blocksPrivateRanges() {
        assertThatThrownBy(() -> strictGuard().validateSync("https://10.0.0.5/"))
                .isInstanceOf(DigiPresBeException.class);
        assertThatThrownBy(() -> strictGuard().validateSync("https://192.168.1.1/"))
                .isInstanceOf(DigiPresBeException.class);
        assertThatThrownBy(() -> strictGuard().validateSync("https://172.16.0.1/"))
                .isInstanceOf(DigiPresBeException.class);
    }

    @Test
    void blocksWildcardAndIpv6Loopback() {
        assertThatThrownBy(() -> strictGuard().validateSync("https://0.0.0.0/"))
                .isInstanceOf(DigiPresBeException.class);
        assertThatThrownBy(() -> strictGuard().validateSync("https://[::1]/"))
                .isInstanceOf(DigiPresBeException.class);
    }

    // ── the allow path ───────────────────────────────────────────────────────────

    @Test
    void allowsNormalPublicHttpsHost() {
        URI uri = strictGuard().validateSync("https://93.184.216.34/webhook");
        assertThat(uri).isNotNull();
        assertThat(uri.getScheme()).isEqualTo("https");
    }

    // ── isBlockedAddress unit (no DNS) ───────────────────────────────────────────

    @Test
    void isBlockedAddress_classifiesUniqueLocalIpv6() throws Exception {
        // fd00::1 is ULA — JDK's isSiteLocalAddress() is false for it, so the explicit
        // fc00::/7 check is what catches it.
        InetAddress ula = InetAddress.getByName("fd00::1");
        assertThat(OutboundUrlGuard.isBlockedAddress(ula)).isTrue();
    }

    @Test
    void isBlockedAddress_allowsPublicIpv4() throws Exception {
        InetAddress publicIp = InetAddress.getByName("93.184.216.34");
        assertThat(OutboundUrlGuard.isBlockedAddress(publicIp)).isFalse();
    }

    // ── the test-profile relaxation skips the IP checks ──────────────────────────

    @Test
    void relaxedPolicyAllowsLoopbackHttp() {
        OutboundUrlGuardProperties relaxed = new OutboundUrlGuardProperties();
        relaxed.setRequireHttps(false);
        relaxed.setAllowPrivateNetworks(true);
        OutboundUrlGuard guard = new OutboundUrlGuard(relaxed);

        URI uri = guard.validateSync("http://127.0.0.1:8089/__admin");
        assertThat(uri).isNotNull();
    }
}
