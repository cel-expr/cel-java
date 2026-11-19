// Copyright 2026 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package dev.cel.extensions;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.testing.EqualsTester;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import dev.cel.common.types.DefaultTypeProvider;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class CelNetworkTypesTest {

  @Test
  public void defaultTypeProvider_registersNetworkOpaqueTypes() {
    DefaultTypeProvider provider = DefaultTypeProvider.getInstance();
    assertThat(provider.findType("net.IP")).hasValue(CelIp.IP_TYPE);
    assertThat(provider.findType("net.CIDR")).hasValue(CelCidr.CIDR_TYPE);
    assertThat(provider.types()).containsAtLeast(CelIp.IP_TYPE, CelCidr.CIDR_TYPE);
    assertThat(provider.findType("net.Unknown")).isEmpty();
  }

  @Test
  public void nullContracts_throwNullPointerException() {
    assertThrows(NullPointerException.class, () -> CelIp.parse(null));
    assertThrows(NullPointerException.class, () -> CelCidr.parse(null));

    CelCidr cidr = CelCidr.parse("10.0.0.0/8");
    assertThrows(NullPointerException.class, () -> cidr.containsIp(null));
    assertThrows(NullPointerException.class, () -> cidr.containsCidr(null));
  }

  @Test
  public void celIp_opaqueValueAndFormatting() {
    CelIp ipv4 = CelIp.parse("192.168.1.42");
    assertThat(ipv4.value()).isSameInstanceAs(ipv4);
    assertThat(ipv4.celType()).isEqualTo(CelIp.IP_TYPE);
    assertThat(ipv4.family()).isEqualTo(4L);
    assertThat(ipv4.toCanonicalString()).isEqualTo("192.168.1.42");
    assertThat(ipv4.toString()).isEqualTo("192.168.1.42");

    CelIp ipv6 = CelIp.parse("2001:0DB8:0:0:1:0:0:1");
    assertThat(ipv6.value()).isSameInstanceAs(ipv6);
    assertThat(ipv6.celType()).isEqualTo(CelIp.IP_TYPE);
    assertThat(ipv6.family()).isEqualTo(6L);
    // RFC 5952: leftmost tie-break for equal-length zero runs, lowercase hex, no leading zeros.
    assertThat(ipv6.toCanonicalString()).isEqualTo("2001:db8::1:0:0:1");
    assertThat(ipv6.toString()).isEqualTo("2001:db8::1:0:0:1");
  }

  @Test
  @TestParameters("{raw: '0.0.0.0', canonical: '0.0.0.0'}")
  @TestParameters("{raw: '255.255.255.255', canonical: '255.255.255.255'}")
  @TestParameters("{raw: '::', canonical: '::'}")
  @TestParameters("{raw: '::1', canonical: '::1'}")
  @TestParameters("{raw: '2001:db8::', canonical: '2001:db8::'}")
  @TestParameters("{raw: '1:2:3:4:5:6:7:8', canonical: '1:2:3:4:5:6:7:8'}")
  @TestParameters("{raw: '2001:db8:0:1:1:1:1:1', canonical: '2001:db8:0:1:1:1:1:1'}")
  @TestParameters("{raw: '2001:0:0:1:0:0:0:1', canonical: '2001:0:0:1::1'}")
  @TestParameters("{raw: '2001:db8:abcd:ABCD::09af:AF', canonical: '2001:db8:abcd:abcd::9af:af'}")
  @TestParameters("{raw: '::192.168.0.1', canonical: '::c0a8:1'}")
  @TestParameters("{raw: '64:ff9b::192.0.2.1', canonical: '64:ff9b::c000:201'}")
  @TestParameters("{raw: '1:2:3:4:5:6:192.168.0.1', canonical: '1:2:3:4:5:6:c0a8:1'}")
  @TestParameters("{raw: '::fffe:192.168.0.1', canonical: '::fffe:c0a8:1'}")
  @TestParameters("{raw: '1::ffff:192.168.0.1', canonical: '1::ffff:c0a8:1'}")
  public void celIp_parseValidAndCanonicalize(String raw, String canonical) {
    CelIp ip = CelIp.parse(raw);
    assertThat(ip.toCanonicalString()).isEqualTo(canonical);
    assertThat(ip.toString()).isEqualTo(canonical);
  }

  @Test
  @TestParameters("{raw: '', errSubstr: 'unable to parse IP'}")
  @TestParameters("{raw: 'not-an-ip', errSubstr: 'unable to parse IP'}")
  @TestParameters("{raw: '01.2.3.4', errSubstr: 'leading zero'}")
  @TestParameters("{raw: '256.0.0.1', errSubstr: '>255'}")
  @TestParameters("{raw: '999.999.999.999', errSubstr: '>255'}")
  @TestParameters("{raw: '.1.2.3', errSubstr: 'at least one digit'}")
  @TestParameters("{raw: '1..2.3.4', errSubstr: 'at least one digit'}")
  @TestParameters("{raw: '1.2.3.4.5', errSubstr: 'too long'}")
  @TestParameters("{raw: '1.2.3.a', errSubstr: 'unexpected character'}")
  @TestParameters("{raw: '1.2.3.-1', errSubstr: 'unexpected character'}")
  @TestParameters("{raw: '1.2.3', errSubstr: 'too short'}")
  @TestParameters("{raw: '1.2.3.', errSubstr: 'too short'}")
  @TestParameters("{raw: '1.2.3.4:80', errSubstr: 'unexpected IPv4 prefix'}")
  @TestParameters("{raw: ':', errSubstr: 'address too short'}")
  @TestParameters("{raw: ':1:2:3:4:5:6:7', errSubstr: 'must have a digit'}")
  @TestParameters("{raw: '20011::1', errSubstr: 'too many digits'}")
  @TestParameters("{raw: '0:0:0:0:0:ffff:0:00000', errSubstr: 'too many digits'}")
  @TestParameters("{raw: '2001:db8::!', errSubstr: 'unexpected character'}")
  @TestParameters("{raw: '2001:db8::@', errSubstr: 'unexpected character'}")
  @TestParameters("{raw: '2001:db8::G', errSubstr: 'unexpected character'}")
  @TestParameters("{raw: '2001:db8::g', errSubstr: 'unexpected character'}")
  @TestParameters("{raw: '2001:db8::/', errSubstr: 'unexpected character'}")
  @TestParameters("{raw: 'gggg::1%en0', errSubstr: 'unexpected character'}")
  @TestParameters("{raw: 'fe80::1%', errSubstr: 'zone must be non-empty'}")
  @TestParameters("{raw: 'fe80::1%en0', errSubstr: 'with zone value is not allowed'}")
  @TestParameters("{raw: '::ffff:192.168.0.1', errSubstr: 'IPv4-mapped IPv6 address'}")
  @TestParameters("{raw: '::ffff:c0a8:1', errSubstr: 'IPv4-mapped IPv6 address'}")
  @TestParameters("{raw: '::ffff:192.168.0.1%en0', errSubstr: 'with zone value is not allowed'}")
  @TestParameters("{raw: '1:2:3:4:5:6:7:1.2.3.4', errSubstr: 'address too long'}")
  @TestParameters("{raw: '1:2:3:4:5:6::1.2.3.4', errSubstr: 'address too long'}")
  @TestParameters("{raw: '1:2:3:4:5:192.168.0.1', errSubstr: 'address too short'}")
  @TestParameters("{raw: '2001::db8::1', errSubstr: 'multiple ::'}")
  @TestParameters("{raw: ':::', errSubstr: 'multiple ::'}")
  @TestParameters("{raw: '1:2:3:4:5:6:7:8:9', errSubstr: 'address too long'}")
  @TestParameters("{raw: '2001:db8:1:', errSubstr: 'trailing colon'}")
  @TestParameters("{raw: '2001:db8::1:', errSubstr: 'trailing colon'}")
  @TestParameters("{raw: '1:2:3:4:5:6:7', errSubstr: 'address too short'}")
  @TestParameters("{raw: '::1:2:3:4:5:6:7:8', errSubstr: 'too long for :: compression'}")
  @TestParameters("{raw: '1:2:3:4::5:6:7:8', errSubstr: 'too long for :: compression'}")
  public void celIp_parseInvalid_throwsIllegalArgumentException(String raw, String errSubstr) {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> CelIp.parse(raw));
    assertThat(e).hasMessageThat().contains(errSubstr);
  }

  @Test
  public void celIp_predicatesCoverAllBranches() {
    assertThat(CelIp.parse("0.0.0.0").isUnspecified()).isTrue();
    assertThat(CelIp.parse("0.0.0.1").isUnspecified()).isFalse();
    assertThat(CelIp.parse("::").isUnspecified()).isTrue();
    assertThat(CelIp.parse("::1").isUnspecified()).isFalse();
    assertThat(CelIp.parse("2001:db8::").isUnspecified()).isFalse();

    assertThat(CelIp.parse("127.0.0.1").isLoopback()).isTrue();
    assertThat(CelIp.parse("127.255.255.254").isLoopback()).isTrue();
    assertThat(CelIp.parse("128.0.0.1").isLoopback()).isFalse();
    assertThat(CelIp.parse("::1").isLoopback()).isTrue();
    assertThat(CelIp.parse("::2").isLoopback()).isFalse();
    assertThat(CelIp.parse("2001:db8::1").isLoopback()).isFalse();

    assertThat(CelIp.parse("169.254.1.1").isLinkLocalUnicast()).isTrue();
    assertThat(CelIp.parse("169.255.0.1").isLinkLocalUnicast()).isFalse();
    assertThat(CelIp.parse("192.168.0.1").isLinkLocalUnicast()).isFalse();
    assertThat(CelIp.parse("fe80::1").isLinkLocalUnicast()).isTrue();
    assertThat(CelIp.parse("febf::1").isLinkLocalUnicast()).isTrue();
    assertThat(CelIp.parse("fe00::1").isLinkLocalUnicast()).isFalse();
    assertThat(CelIp.parse("fec0::1").isLinkLocalUnicast()).isFalse();
    assertThat(CelIp.parse("fd00::1").isLinkLocalUnicast()).isFalse();

    assertThat(CelIp.parse("224.0.0.1").isLinkLocalMulticast()).isTrue();
    assertThat(CelIp.parse("224.0.1.1").isLinkLocalMulticast()).isFalse();
    assertThat(CelIp.parse("224.1.0.1").isLinkLocalMulticast()).isFalse();
    assertThat(CelIp.parse("239.0.0.1").isLinkLocalMulticast()).isFalse();
    assertThat(CelIp.parse("ff02::1").isLinkLocalMulticast()).isTrue();
    assertThat(CelIp.parse("ff12::1").isLinkLocalMulticast()).isTrue();
    assertThat(CelIp.parse("ff05::1").isLinkLocalMulticast()).isFalse();
    assertThat(CelIp.parse("fe80::1").isLinkLocalMulticast()).isFalse();

    assertThat(CelIp.parse("255.255.255.255").isGlobalUnicast()).isFalse();
    assertThat(CelIp.parse("0.0.0.0").isGlobalUnicast()).isFalse();
    assertThat(CelIp.parse("127.0.0.1").isGlobalUnicast()).isFalse();
    assertThat(CelIp.parse("224.0.0.1").isGlobalUnicast()).isFalse();
    assertThat(CelIp.parse("239.255.255.255").isGlobalUnicast()).isFalse();
    assertThat(CelIp.parse("169.254.0.1").isGlobalUnicast()).isFalse();
    assertThat(CelIp.parse("192.168.1.1").isGlobalUnicast()).isTrue();
    assertThat(CelIp.parse("::").isGlobalUnicast()).isFalse();
    assertThat(CelIp.parse("::1").isGlobalUnicast()).isFalse();
    assertThat(CelIp.parse("ff00::1").isGlobalUnicast()).isFalse();
    assertThat(CelIp.parse("fe80::1").isGlobalUnicast()).isFalse();
    assertThat(CelIp.parse("::ffff:ffff").isGlobalUnicast()).isTrue();
    assertThat(CelIp.parse("2001:db8::1").isGlobalUnicast()).isTrue();
  }

  @Test
  public void celCidr_opaqueValueMaskingAndContainment() {
    CelCidr v4 = CelCidr.parse("192.168.1.5/24");
    assertThat(v4.value()).isSameInstanceAs(v4);
    assertThat(v4.celType()).isEqualTo(CelCidr.CIDR_TYPE);
    assertThat(v4.ip()).isEqualTo(CelIp.parse("192.168.1.5"));
    assertThat(v4.prefixLength()).isEqualTo(24L);
    assertThat(v4.isMask()).isFalse();
    assertThat(v4.toCanonicalString()).isEqualTo("192.168.1.5/24");
    assertThat(v4.toString()).isEqualTo("192.168.1.5/24");

    CelCidr v4Masked = v4.masked();
    assertThat(v4Masked).isEqualTo(CelCidr.parse("192.168.1.0/24"));
    assertThat(v4Masked.isMask()).isTrue();
    assertThat(CelCidr.parse("192.168.1.5/0").masked()).isEqualTo(CelCidr.parse("0.0.0.0/0"));
    assertThat(CelCidr.parse("192.168.1.5/32").masked()).isEqualTo(CelCidr.parse("192.168.1.5/32"));

    // IPv4 /0 vs IPv6 containment
    CelCidr v4Zero = CelCidr.parse("0.0.0.0/0");
    assertThat(v4Zero.containsIp(CelIp.parse("192.168.1.1"))).isTrue();
    assertThat(v4Zero.containsIp(CelIp.parse("::1"))).isFalse();

    // IPv6 masking across /0, /32 (<64), /64, /96 and /112 (65..127), and /128
    assertThat(CelCidr.parse("2001:db8::1/0").masked()).isEqualTo(CelCidr.parse("::/0"));
    assertThat(CelCidr.parse("2001:db8:abcd::1/32").masked())
        .isEqualTo(CelCidr.parse("2001:db8::/32"));
    assertThat(CelCidr.parse("2001:db8:1:2:3:4:5:6/64").masked())
        .isEqualTo(CelCidr.parse("2001:db8:1:2::/64"));
    assertThat(CelCidr.parse("2001:db8::1234:dead:beef/96").masked())
        .isEqualTo(CelCidr.parse("2001:db8::1234:0:0/96"));
    assertThat(CelCidr.parse("2001:db8::dead:beef/112").masked())
        .isEqualTo(CelCidr.parse("2001:db8::dead:0/112"));
    assertThat(CelCidr.parse("2001:db8::dead:beef/128").masked())
        .isEqualTo(CelCidr.parse("2001:db8::dead:beef/128"));

    // IPv6 /112 containment: hi match + lo match vs hi match + lo mismatch vs hi mismatch
    CelCidr v6Slash112 = CelCidr.parse("2001:db8::dead:0/112");
    assertThat(v6Slash112.containsIp(CelIp.parse("2001:db8::dead:beef"))).isTrue();
    assertThat(v6Slash112.containsIp(CelIp.parse("2001:db8::beef:0"))).isFalse();
    assertThat(v6Slash112.containsIp(CelIp.parse("2001:db9::dead:0"))).isFalse();

    // containsCidr branches: cross-family, narrower parent, disjoint, equal, and unmasked parent
    assertThat(v4Zero.containsCidr(CelCidr.parse("::/0"))).isFalse();
    assertThat(CelCidr.parse("10.0.0.0/16").containsCidr(CelCidr.parse("10.0.0.0/8"))).isFalse();
    assertThat(CelCidr.parse("10.0.0.0/8").containsCidr(CelCidr.parse("11.0.0.0/16"))).isFalse();
    assertThat(CelCidr.parse("10.0.0.5/8").containsCidr(CelCidr.parse("10.1.2.0/24"))).isTrue();
  }

  @Test
  @TestParameters("{raw: '1.2.3.4', errSubstr: \"no '/'\"}")
  @TestParameters("{raw: 'invalid/24', errSubstr: 'unable to parse IP'}")
  @TestParameters("{raw: '/24', errSubstr: 'unable to parse IP'}")
  @TestParameters("{raw: '999.0.0.1/24', errSubstr: '>255'}")
  @TestParameters("{raw: '10.0.0.0/8/16', errSubstr: 'unexpected character'}")
  @TestParameters("{raw: 'fe80::1%/64', errSubstr: 'zone must be non-empty'}")
  @TestParameters("{raw: 'fe80::1%en0/64', errSubstr: 'with zone value is not allowed'}")
  @TestParameters("{raw: 'fe80::1%en0/999', errSubstr: 'with zone value is not allowed'}")
  @TestParameters("{raw: '::ffff:192.168.0.1/24', errSubstr: 'IPv4-mapped IPv6 address'}")
  @TestParameters("{raw: '::ffff:192.168.0.1/999', errSubstr: 'IPv4-mapped IPv6 address'}")
  @TestParameters("{raw: '192.168.0.0/', errSubstr: 'empty prefix'}")
  @TestParameters("{raw: '2001:db8::/', errSubstr: 'empty prefix'}")
  @TestParameters("{raw: '10.0.0.0/08', errSubstr: 'leading zero'}")
  @TestParameters("{raw: '10.0.0.0/00', errSubstr: 'leading zero'}")
  @TestParameters("{raw: '10.0.0.0/1000', errSubstr: 'out of range'}")
  @TestParameters("{raw: 'fe80::1/64%en0', errSubstr: 'out of range'}")
  @TestParameters("{raw: '10.0.0.0/-1', errSubstr: 'invalid prefix length'}")
  @TestParameters("{raw: '10.0.0.0/2a', errSubstr: 'invalid prefix length'}")
  @TestParameters("{raw: '10.0.0.0/33', errSubstr: 'out of range'}")
  @TestParameters("{raw: '2001:db8::/129', errSubstr: 'out of range'}")
  public void celCidr_parseInvalid_throwsSinglePrefixedIllegalArgumentException(
      String raw, String errSubstr) {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> CelCidr.parse(raw));
    assertThat(e).hasMessageThat().contains(errSubstr);
    // Verify no double "CIDR '...'" error prefix wrapping on invalid IP portions.
    String msg = e.getMessage();
    int firstIdx = msg.indexOf("parse error during conversion from string:");
    if (firstIdx >= 0) {
      assertThat(msg.indexOf("parse error during conversion from string:", firstIdx + 1))
          .isEqualTo(-1);
      assertThat(msg).startsWith("CIDR '" + raw + "' parse error during conversion from string:");
    }
  }

  @Test
  public void equalsAndHashCode() {
    new EqualsTester()
        .addEqualityGroup(CelIp.parse("0.0.0.0"))
        .addEqualityGroup(CelIp.parse("::"), CelIp.parse("0:0:0:0:0:0:0:0"))
        .addEqualityGroup(CelIp.parse("0.0.0.1"))
        .addEqualityGroup(CelIp.parse("::1"), CelIp.parse("0:0:0:0:0:0:0:1"))
        .addEqualityGroup(
            CelIp.parse("2001:db8::1"),
            CelIp.parse("2001:DB8::1"),
            CelIp.parse("2001:0db8:0:0:0:0:0:1"))
        .addEqualityGroup(CelIp.parse("2001:db8::2"))
        .addEqualityGroup(CelIp.parse("2001:db9::1"))
        .addEqualityGroup(CelCidr.parse("0.0.0.0/0"))
        .addEqualityGroup(CelCidr.parse("::/0"), CelCidr.parse("0:0:0:0:0:0:0:0/0"))
        .addEqualityGroup(CelCidr.parse("10.0.0.0/8"))
        .addEqualityGroup(CelCidr.parse("10.0.0.0/16"))
        .addEqualityGroup(CelCidr.parse("10.0.0.1/8"))
        .addEqualityGroup(
            CelCidr.parse("2001:db8::/32"),
            CelCidr.parse("2001:DB8::/32"),
            CelCidr.parse("2001:0db8:0:0:0:0:0:0/32"))
        .testEquals();
  }
}
