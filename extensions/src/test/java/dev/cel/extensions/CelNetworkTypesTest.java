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
    assertThat(provider.types()).contains(CelIp.IP_TYPE);
    assertThat(provider.findType("net.Unknown")).isEmpty();
  }

  @Test
  public void nullContracts_throwNullPointerException() {
    assertThrows(NullPointerException.class, () -> CelIp.parse(null));
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
  @TestParameters("{raw: '0.9.0.9', canonical: '0.9.0.9'}")
  @TestParameters("{raw: '255.255.255.255', canonical: '255.255.255.255'}")
  @TestParameters("{raw: '::', canonical: '::'}")
  @TestParameters("{raw: '::1', canonical: '::1'}")
  @TestParameters("{raw: '0:0:2:3:4:5:6:7', canonical: '::2:3:4:5:6:7'}")
  @TestParameters("{raw: '2001:db8::', canonical: '2001:db8::'}")
  @TestParameters("{raw: '2001:db8::dead:BEEF', canonical: '2001:db8::dead:beef'}")
  @TestParameters("{raw: '1:2:3:4:5:6:7:8', canonical: '1:2:3:4:5:6:7:8'}")
  @TestParameters("{raw: '2001:db8:0:1:1:1:1:1', canonical: '2001:db8:0:1:1:1:1:1'}")
  @TestParameters("{raw: '2001:0:0:1:2:3:4:5', canonical: '2001::1:2:3:4:5'}")
  @TestParameters("{raw: '2001:0:1:0:2:3:4:5', canonical: '2001:0:1:0:2:3:4:5'}")
  @TestParameters("{raw: '2001:0:0:1:0:0:0:1', canonical: '2001:0:0:1::1'}")
  @TestParameters("{raw: '2001:db8:abcd:ABCD::09af:AF', canonical: '2001:db8:abcd:abcd::9af:af'}")
  @TestParameters("{raw: '::192.168.0.1', canonical: '::c0a8:1'}")
  @TestParameters("{raw: '64:ff9b::192.0.2.1', canonical: '64:ff9b::c000:201'}")
  @TestParameters("{raw: '1:2:3:4:5::192.168.0.1', canonical: '1:2:3:4:5:0:c0a8:1'}")
  @TestParameters("{raw: '1:2:3:4:5:6:192.168.0.1', canonical: '1:2:3:4:5:6:c0a8:1'}")
  @TestParameters("{raw: '1:2:3:4::5:6:7', canonical: '1:2:3:4:0:5:6:7'}")
  @TestParameters(
      "{raw: 'fedc:ba98:7654:fedc:89ab:cdef:0123:ffff',"
          + " canonical: 'fedc:ba98:7654:fedc:89ab:cdef:123:ffff'}")
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
  @TestParameters("{raw: '%::1', errSubstr: 'address too short'}")
  @TestParameters("{raw: ':1:2:3:4:5:6:7', errSubstr: 'must have a digit'}")
  @TestParameters("{raw: '20011::1', errSubstr: 'too many digits'}")
  @TestParameters("{raw: '0:0:0:0:0:ffff:0:00000', errSubstr: 'too many digits'}")
  @TestParameters("{raw: '2001:db8::!', errSubstr: 'unexpected character'}")
  @TestParameters("{raw: '2001:db8::@', errSubstr: 'unexpected character'}")
  @TestParameters("{raw: '2001:db8::`', errSubstr: 'unexpected character'}")
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
  public void celIp_isUnspecified_returnsExpected() {
    assertThat(CelIp.parse("0.0.0.0").isUnspecified()).isTrue();
    assertThat(CelIp.parse("0.0.0.1").isUnspecified()).isFalse();
    assertThat(CelIp.parse("::").isUnspecified()).isTrue();
    assertThat(CelIp.parse("::1").isUnspecified()).isFalse();
    assertThat(CelIp.parse("2001:db8::").isUnspecified()).isFalse();
  }

  @Test
  public void celIp_isLoopback_returnsExpected() {
    assertThat(CelIp.parse("126.255.255.255").isLoopback()).isFalse();
    assertThat(CelIp.parse("127.0.0.1").isLoopback()).isTrue();
    assertThat(CelIp.parse("127.255.255.254").isLoopback()).isTrue();
    assertThat(CelIp.parse("128.0.0.1").isLoopback()).isFalse();
    assertThat(CelIp.parse("::1").isLoopback()).isTrue();
    assertThat(CelIp.parse("::2").isLoopback()).isFalse();
    assertThat(CelIp.parse("2001:db8::1").isLoopback()).isFalse();
  }

  @Test
  public void celIp_isLinkLocalUnicast_returnsExpected() {
    assertThat(CelIp.parse("169.254.1.1").isLinkLocalUnicast()).isTrue();
    assertThat(CelIp.parse("169.255.0.1").isLinkLocalUnicast()).isFalse();
    assertThat(CelIp.parse("192.168.0.1").isLinkLocalUnicast()).isFalse();
    assertThat(CelIp.parse("fe80::1").isLinkLocalUnicast()).isTrue();
    assertThat(CelIp.parse("febf::1").isLinkLocalUnicast()).isTrue();
    assertThat(CelIp.parse("fe00::1").isLinkLocalUnicast()).isFalse();
    assertThat(CelIp.parse("fec0::1").isLinkLocalUnicast()).isFalse();
    assertThat(CelIp.parse("fd00::1").isLinkLocalUnicast()).isFalse();
  }

  @Test
  public void celIp_isLinkLocalMulticast_returnsExpected() {
    assertThat(CelIp.parse("224.0.0.1").isLinkLocalMulticast()).isTrue();
    assertThat(CelIp.parse("224.0.1.1").isLinkLocalMulticast()).isFalse();
    assertThat(CelIp.parse("224.1.0.1").isLinkLocalMulticast()).isFalse();
    assertThat(CelIp.parse("239.0.0.1").isLinkLocalMulticast()).isFalse();
    assertThat(CelIp.parse("ff02::1").isLinkLocalMulticast()).isTrue();
    assertThat(CelIp.parse("ff12::1").isLinkLocalMulticast()).isTrue();
    assertThat(CelIp.parse("ff05::1").isLinkLocalMulticast()).isFalse();
    assertThat(CelIp.parse("fe80::1").isLinkLocalMulticast()).isFalse();
  }

  @Test
  public void celIp_isGlobalUnicast_returnsExpected() {
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
        .testEquals();
  }
}
