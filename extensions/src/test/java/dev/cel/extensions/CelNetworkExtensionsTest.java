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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelErrorCode;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOptions;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelVarDecl;
import dev.cel.common.exceptions.CelBadFormatException;
import dev.cel.common.types.TypeType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelLiteRuntime;
import dev.cel.runtime.CelLiteRuntimeFactory;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import dev.cel.runtime.CelStandardFunctions;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class CelNetworkExtensionsTest extends CelExtensionTestBase {

  @Override
  protected Cel newCelEnv() {
    return runtimeFlavor
        .builder()
        .addCompilerLibraries(CelExtensions.network())
        .addRuntimeLibraries(CelExtensions.network())
        .build();
  }

  @Test
  public void library() {
    CelExtensionLibrary<?> library =
        CelExtensions.getExtensionLibrary("network", CelOptions.DEFAULT);
    assertThat(library.name()).isEqualTo("network");
    assertThat(library.latest().version()).isEqualTo(1);
    assertThat(library.version(0).functions().stream().map(CelFunctionDecl::name))
        .containsExactly(
            "cidr",
            "string",
            "containsCIDR",
            "containsIP",
            "family",
            "ip",
            "ip.isCanonical",
            "isCIDR",
            "isGlobalUnicast",
            "isIP",
            "isLinkLocalMulticast",
            "isLinkLocalUnicast",
            "isLoopback",
            "isMask",
            "isUnspecified",
            "masked",
            "prefixLength");
    assertThat(library.version(1).variables().stream().map(CelVarDecl::name))
        .containsExactly("net.IP", "net.CIDR");
    assertThat(library.version(1).macros()).isEmpty();
  }

  @Test
  @TestParameters("{expr: \"cidr('192.168.0.0/24').ip() == ip('192.168.0.0')\"}")
  @TestParameters("{expr: \"cidr('192.168.1.5/24').ip() == ip('192.168.1.5')\"}")
  @TestParameters("{expr: \"cidr('192.168.1.5/24').masked() == cidr('192.168.1.0/24')\"}")
  @TestParameters("{expr: \"cidr('192.168.1.0/24').masked() == cidr('192.168.1.0/24')\"}")
  @TestParameters("{expr: \"cidr('192.168.1.5/24') != cidr('192.168.1.0/24')\"}")
  @TestParameters("{expr: \"cidr('0.0.0.0/0').masked() == cidr('0.0.0.0/0')\"}")
  @TestParameters("{expr: \"cidr('255.255.255.255/0').masked() == cidr('0.0.0.0/0')\"}")
  @TestParameters("{expr: \"cidr('192.168.1.5/32').masked() == cidr('192.168.1.5/32')\"}")
  @TestParameters("{expr: \"cidr('10.0.0.0/8').containsCIDR(cidr('10.0.0.0/8'))\"}")
  @TestParameters("{expr: \"cidr('10.0.0.0/8').containsCIDR(cidr('10.1.0.0/16'))\"}")
  @TestParameters("{expr: \"cidr('10.0.0.0/8').containsCIDR('10.1.0.0/16')\"}")
  @TestParameters("{expr: \"cidr('192.168.0.0/24').containsCIDR('192.168.0.5/24')\"}")
  @TestParameters("{expr: \"!cidr('10.0.0.0/8').containsCIDR(cidr('::1/128'))\"}")
  @TestParameters("{expr: \"!cidr('10.0.0.0/8').containsCIDR(cidr('11.0.0.0/8'))\"}")
  @TestParameters("{expr: \"!cidr('10.0.0.0/8').containsCIDR(cidr('0.0.0.0/4'))\"}")
  @TestParameters("{expr: \"cidr('10.0.0.0/8').containsIP(ip('10.255.255.255'))\"}")
  @TestParameters("{expr: \"cidr('10.0.0.0/8').containsIP(ip('10.0.0.0'))\"}")
  @TestParameters("{expr: \"cidr('10.0.0.0/8').containsIP(ip('10.1.2.3'))\"}")
  @TestParameters("{expr: \"cidr('10.0.0.0/8').containsIP('10.1.2.3')\"}")
  @TestParameters("{expr: \"cidr('192.168.1.5/24').containsIP('192.168.1.200')\"}")
  @TestParameters("{expr: \"!cidr('10.0.0.0/8').containsIP(ip('11.0.0.0'))\"}")
  @TestParameters("{expr: \"!cidr('2001:db8::/32').containsIP(ip('192.168.1.1'))\"}")
  @TestParameters("{expr: \"!cidr('192.168.1.1/32').containsIP(ip('2001:db8::1'))\"}")
  @TestParameters("{expr: \"ip('127.0.0.1') == ip('127.0.0.1')\"}")
  @TestParameters("{expr: \"ip('2001:db8::1') == ip('2001:DB8::1')\"}")
  @TestParameters("{expr: \"ip('127.0.0.1') != ip('1.2.3.4')\"}")
  @TestParameters("{expr: \"ip('192.168.0.1') != ip('2001:db8::1')\"}")
  @TestParameters("{expr: \"ip('0.0.0.0') != ip('::')\"}")
  @TestParameters("{expr: \"ip('0.0.0.1') != ip('::1')\"}")
  @TestParameters("{expr: \"cidr('0.0.0.0/0') != cidr('::/0')\"}")
  @TestParameters("{expr: \"ip('::') != ip('::ffff')\"}")
  @TestParameters("{expr: \"dyn(ip('10.0.0.1')) != dyn(cidr('10.0.0.1/32'))\"}")
  @TestParameters("{expr: \"dyn(ip('0.0.0.0')) != dyn(ip('::'))\"}")
  @TestParameters("{expr: \"dyn(cidr('0.0.0.0/0')) != dyn(cidr('::/0'))\"}")
  @TestParameters("{expr: \"dyn(ip('10.0.0.1')) != '10.0.0.1'\"}")
  @TestParameters("{expr: \"type(dyn(ip('10.0.0.1'))) == net.IP\"}")
  @TestParameters("{expr: \"type(dyn(cidr('10.0.0.0/8'))) == net.CIDR\"}")
  @TestParameters("{expr: \"ip.isCanonical('127.0.0.1')\"}")
  @TestParameters("{expr: \"!ip.isCanonical('2001:db8:0:0:0:0:0:1')\"}")
  @TestParameters("{expr: \"ip.isCanonical('2001:db8::1')\"}")
  @TestParameters("{expr: \"!ip.isCanonical('2001:DB8::1')\"}")
  @TestParameters("{expr: \"ip.isCanonical('2001:db8:0:1::1')\"}")
  @TestParameters("{expr: \"!ip.isCanonical('2001:db8::1:1:1:1:1')\"}")
  @TestParameters("{expr: \"ip.isCanonical('2001:db8:0:1:1:1:1:1')\"}")
  @TestParameters("{expr: \"ip.isCanonical('::')\"}")
  @TestParameters("{expr: \"ip('8.8.8.8').isGlobalUnicast()\"}")
  @TestParameters("{expr: \"ip('192.168.0.1').isGlobalUnicast()\"}")
  @TestParameters("{expr: \"!ip('255.255.255.255').isGlobalUnicast()\"}")
  @TestParameters("{expr: \"!ip('0.0.0.0').isGlobalUnicast()\"}")
  @TestParameters("{expr: \"!ip('127.0.0.1').isGlobalUnicast()\"}")
  @TestParameters("{expr: \"!ip('224.0.0.1').isGlobalUnicast()\"}")
  @TestParameters("{expr: \"!ip('169.254.0.1').isGlobalUnicast()\"}")
  @TestParameters("{expr: \"ip('2001:db8::abcd').isGlobalUnicast()\"}")
  @TestParameters("{expr: \"!ip('ff00::1').isGlobalUnicast()\"}")
  @TestParameters("{expr: \"!ip('::').isGlobalUnicast()\"}")
  @TestParameters("{expr: \"!ip('::1').isGlobalUnicast()\"}")
  @TestParameters("{expr: \"!ip('fe80::1').isGlobalUnicast()\"}")
  @TestParameters("{expr: \"ip('ff02::1').isLinkLocalMulticast()\"}")
  @TestParameters("{expr: \"ip('ff12::1').isLinkLocalMulticast()\"}")
  @TestParameters("{expr: \"!ip('ff05::1').isLinkLocalMulticast()\"}")
  @TestParameters("{expr: \"!ip('fd00::1').isLinkLocalMulticast()\"}")
  @TestParameters("{expr: \"ip('224.0.0.1').isLinkLocalMulticast()\"}")
  @TestParameters("{expr: \"!ip('224.0.1.1').isLinkLocalMulticast()\"}")
  @TestParameters("{expr: \"ip('169.254.169.254').isLinkLocalUnicast()\"}")
  @TestParameters("{expr: \"!ip('192.168.0.1').isLinkLocalUnicast()\"}")
  @TestParameters("{expr: \"ip('fe80::1').isLinkLocalUnicast()\"}")
  @TestParameters("{expr: \"!ip('fd80::1').isLinkLocalUnicast()\"}")
  @TestParameters("{expr: \"ip('127.0.0.1').isLoopback()\"}")
  @TestParameters("{expr: \"!ip('1.2.3.4').isLoopback()\"}")
  @TestParameters("{expr: \"ip('::1').isLoopback()\"}")
  @TestParameters("{expr: \"!ip('2001:db8::abcd').isLoopback()\"}")
  @TestParameters("{expr: \"ip('0.0.0.0').isUnspecified()\"}")
  @TestParameters("{expr: \"!ip('127.0.0.1').isUnspecified()\"}")
  @TestParameters("{expr: \"ip('::').isUnspecified()\"}")
  @TestParameters("{expr: \"!ip('::1').isUnspecified()\"}")
  @TestParameters("{expr: \"isCIDR('10.0.0.0/8')\"}")
  @TestParameters("{expr: \"isCIDR('10.0.0.1/8')\"}")
  @TestParameters("{expr: \"isCIDR('2001:db8::/32')\"}")
  @TestParameters("{expr: \"isCIDR('::/0')\"}")
  @TestParameters("{expr: \"!isCIDR('10.0.0.0/999')\"}")
  @TestParameters("{expr: \"!isCIDR('10.0.0.0/08')\"}")
  @TestParameters("{expr: \"!isCIDR('10.0.0.0/00')\"}")
  @TestParameters("{expr: \"!isCIDR('10.0.0.0/-1')\"}")
  @TestParameters("{expr: \"!isCIDR('10.0.0.0/')\"}")
  @TestParameters("{expr: \"!isCIDR('10.0.0.0')\"}")
  @TestParameters("{expr: \"!isCIDR('10.0.0.0/33')\"}")
  @TestParameters("{expr: \"!isCIDR('::/129')\"}")
  @TestParameters("{expr: \"!isCIDR('fe80::1%en0/24')\"}")
  @TestParameters("{expr: \"!isCIDR('::ffff:192.168.0.1/24')\"}")
  @TestParameters("{expr: \"isIP('1.2.3.4')\"}")
  @TestParameters("{expr: \"isIP('2001:db8::1')\"}")
  @TestParameters("{expr: \"isIP('64:ff9b::192.0.2.1')\"}")
  @TestParameters("{expr: \"!isIP('')\"}")
  @TestParameters("{expr: \"!isIP('not.an.ip')\"}")
  @TestParameters("{expr: \"!isIP('127.0.0.1:80')\"}")
  @TestParameters("{expr: \"!isIP('01.2.3.4')\"}")
  @TestParameters("{expr: \"!isIP('192.168.001.1')\"}")
  @TestParameters("{expr: \"!isIP('192.168.0.1.0')\"}")
  @TestParameters("{expr: \"!isIP('192.168.0.')\"}")
  @TestParameters("{expr: \"!isIP('.192.168.0.1')\"}")
  @TestParameters("{expr: \"!isIP('2001:db8:::68')\"}")
  @TestParameters("{expr: \"!isIP('1::2::3')\"}")
  @TestParameters("{expr: \"!isIP('1:2:3:4:5:6:7::8')\"}")
  @TestParameters("{expr: \"!isIP('fe80::1%en0')\"}")
  @TestParameters("{expr: \"!isIP('::ffff:192.168.0.1')\"}")
  @TestParameters("{expr: \"!isIP('::ffff:c0a8:1')\"}")
  @TestParameters("{expr: \"cidr('10.0.0.0/8').isMask()\"}")
  @TestParameters("{expr: \"!cidr('10.0.0.1/8').isMask()\"}")
  @TestParameters("{expr: \"cidr('2001:db8::/32').isMask()\"}")
  @TestParameters("{expr: \"!cidr('2001:db8::1/32').isMask()\"}")
  @TestParameters("{expr: \"cidr('2001:db8::/32').containsIP(ip('2001:db8::1'))\"}")
  @TestParameters("{expr: \"!cidr('2001:db8::/32').containsIP(ip('2001:dc8::1'))\"}")
  @TestParameters("{expr: \"cidr('2001:db8::/32').containsIP('2001:db8::1')\"}")
  @TestParameters("{expr: \"!cidr('2001:db8::/32').containsIP('2001:dc8::1')\"}")
  @TestParameters("{expr: \"cidr('2001:db8::/32').containsCIDR(cidr('2001:db8::/33'))\"}")
  @TestParameters("{expr: \"!cidr('2001:db8::/32').containsCIDR(cidr('2001:db8::/31'))\"}")
  @TestParameters("{expr: \"cidr('2001:db8::/32').containsCIDR('2001:db8::/33')\"}")
  @TestParameters("{expr: \"!cidr('2001:db8::/32').containsCIDR('2001:db8::/31')\"}")
  @TestParameters("{expr: \"cidr('2001:db8::/32').ip() == ip('2001:db8::')\"}")
  @TestParameters("{expr: \"cidr('2001:db8::/32').masked() == cidr('2001:db8::/32')\"}")
  @TestParameters("{expr: \"cidr('2001:db8:1::/32').masked() == cidr('2001:db8::/32')\"}")
  @TestParameters(
      "{expr: \"cidr('ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff/64').masked() =="
          + " cidr('ffff:ffff:ffff:ffff::/64')\"}")
  @TestParameters("{expr: \"cidr('ffff:ffff::/0').masked() == cidr('::/0')\"}")
  @TestParameters("{expr: \"cidr('2001:db8::1/128').masked() == cidr('2001:db8::1/128')\"}")
  @TestParameters(
      "{expr: \"cidr('2001:db8::dead:beef/112').masked() == cidr('2001:db8::dead:0/112')\"}")
  @TestParameters("{expr: \"cidr('2001:db8::/96').containsIP('2001:db8::1')\"}")
  @TestParameters("{expr: \"cidr('0.0.0.0/0').containsIP('192.168.1.1')\"}")
  @TestParameters("{expr: \"!cidr('0.0.0.0/0').containsIP('::1')\"}")
  @TestParameters("{expr: \"!ip('169.255.0.1').isLinkLocalUnicast()\"}")
  @TestParameters("{expr: \"!ip('fec0::1').isLinkLocalUnicast()\"}")
  @TestParameters("{expr: \"!ip('224.1.0.1').isLinkLocalMulticast()\"}")
  @TestParameters("{expr: \"ip('::ffff:ffff').isGlobalUnicast()\"}")
  @TestParameters("{expr: \"isIP('::192.168.0.1')\"}")
  @TestParameters("{expr: \"!isIP('192.168.300.1')\"}")
  @TestParameters("{expr: \"!isIP('fe80::1%')\"}")
  @TestParameters("{expr: \"!isIP('gggg::1%en0')\"}")
  @TestParameters("{expr: \"!isIP('1.2.3')\"}")
  @TestParameters("{expr: \"!isIP('1..2.3.4')\"}")
  @TestParameters("{expr: \"!isIP(':')\"}")
  @TestParameters("{expr: \"!isIP('fe801::1')\"}")
  @TestParameters("{expr: \"!isIP('gggg::1')\"}")
  @TestParameters("{expr: \"!isIP('1:2:3:4:5:6:7:1.2.3.4')\"}")
  @TestParameters("{expr: \"!isIP('1:2:3:4:5:6::1.2.3.4')\"}")
  @TestParameters("{expr: \"!isIP('1:2:3:4:5:6:7:8:9')\"}")
  @TestParameters("{expr: \"!isIP('1:2:3:4:5:6:7')\"}")
  @TestParameters("{expr: \"!isIP('1:2:3:4:5:192.168.0.1')\"}")
  @TestParameters("{expr: \"!isCIDR('999.0.0.1/24')\"}")
  @TestParameters("{expr: \"!isCIDR('10.0.0.0/8/16')\"}")
  @TestParameters("{expr: \"!isCIDR('10.0.0.0/1000')\"}")
  @TestParameters("{expr: \"!isCIDR('fe80::1/64%en0')\"}")
  @TestParameters("{expr: \"!isCIDR('10.0.0.0/ab')\"}")
  @TestParameters("{expr: \"type(ip('192.168.0.1')) == net.IP\"}")
  @TestParameters("{expr: \"type(cidr('192.168.0.0/24')) == net.CIDR\"}")
  @TestParameters("{expr: \"type(ip('::1')) != net.CIDR\"}")
  @TestParameters("{expr: \"net.IP != net.CIDR\"}")
  @TestParameters("{expr: \"string(42) + ' ' + string(ip('1.2.3.4')) == '42 1.2.3.4'\"}")
  public void networkExpressions_true(String expr) throws Exception {
    assertThat(eval(expr)).isEqualTo(true);
  }

  @Test
  @TestParameters("{expr: \"string(cidr('10.0.0.0/8'))\", expected: '10.0.0.0/8'}")
  @TestParameters("{expr: \"string(cidr('192.168.1.5/24'))\", expected: '192.168.1.5/24'}")
  @TestParameters("{expr: \"string(cidr('::1/128'))\", expected: '::1/128'}")
  @TestParameters("{expr: \"string(cidr('2001:0DB8:0:0::1/64'))\", expected: '2001:db8::1/64'}")
  @TestParameters("{expr: \"string(ip('1.2.3.4'))\", expected: '1.2.3.4'}")
  @TestParameters("{expr: \"string(ip('2001:db8::1'))\", expected: '2001:db8::1'}")
  @TestParameters("{expr: \"string(ip('2001:0DB8:0:0:0:0:0:1'))\", expected: '2001:db8::1'}")
  @TestParameters("{expr: \"string(ip('2001:0:0:1:0:0:0:1'))\", expected: '2001:0:0:1::1'}")
  @TestParameters("{expr: \"string(ip('2001:0:0:1:1:0:0:1'))\", expected: '2001::1:1:0:0:1'}")
  @TestParameters(
      "{expr: \"string(ip('2001:db8:0:1:1:1:1:1'))\", expected: '2001:db8:0:1:1:1:1:1'}")
  @TestParameters("{expr: \"string(ip('64:ff9b::192.0.2.1'))\", expected: '64:ff9b::c000:201'}")
  @TestParameters("{expr: \"string(ip('0:0:0:0:0:0:0:0'))\", expected: '::'}")
  public void stringConversion_success(String expr, String expected) throws Exception {
    assertThat(eval(expr)).isEqualTo(expected);
  }

  @Test
  @TestParameters("{expr: \"ip('127.0.0.1').family()\", expected: 4}")
  @TestParameters("{expr: \"ip('::1').family()\", expected: 6}")
  @TestParameters("{expr: \"cidr('192.168.0.0/24').prefixLength()\", expected: 24}")
  @TestParameters("{expr: \"cidr('2001:db8::/32').prefixLength()\", expected: 32}")
  public void intReturnFunctions_success(String expr, long expected) throws Exception {
    assertThat(eval(expr)).isEqualTo(expected);
  }

  @Test
  @TestParameters("{expr: \"ip('192.168.0.1.0')\", errSubstr: 'parse error'}")
  @TestParameters("{expr: \"ip('01.2.3.4')\", errSubstr: 'parse error'}")
  @TestParameters("{expr: \"ip('2001:db8:::68')\", errSubstr: 'parse error'}")
  @TestParameters("{expr: \"ip('2001:db8:')\", errSubstr: 'parse error'}")
  @TestParameters("{expr: \"ip(':2001:db8::1')\", errSubstr: 'parse error'}")
  @TestParameters("{expr: \"ip('192.168.0.1%eth0')\", errSubstr: 'parse error'}")
  @TestParameters("{expr: \"ip('fe80::1%en0')\", errSubstr: 'with zone value is not allowed'}")
  @TestParameters("{expr: \"ip('::ffff:192.168.0.1')\", errSubstr: 'IPv4-mapped IPv6 address'}")
  @TestParameters("{expr: \"ip('::ffff:c0a8:1')\", errSubstr: 'IPv4-mapped IPv6 address'}")
  @TestParameters("{expr: \"ip('::FFFF:192.168.0.1')\", errSubstr: 'IPv4-mapped IPv6 address'}")
  @TestParameters("{expr: \"ip.isCanonical('127.0.0.1.0')\", errSubstr: 'parse error'}")
  @TestParameters("{expr: \"ip.isCanonical('2001:db8:::68')\", errSubstr: 'parse error'}")
  @TestParameters("{expr: \"ip.isCanonical('fe80::1%en0')\", errSubstr: 'with zone value'}")
  @TestParameters("{expr: \"cidr('192.168.0.0/')\", errSubstr: 'parse error'}")
  @TestParameters("{expr: \"cidr('10.0.0.0/08')\", errSubstr: 'parse error'}")
  @TestParameters("{expr: \"cidr('10.0.0.0/999')\", errSubstr: 'parse error'}")
  @TestParameters("{expr: \"cidr('fe80::1%en0/24')\", errSubstr: 'with zone value is not allowed'}")
  @TestParameters(
      "{expr: \"cidr('::ffff:192.168.0.1/24')\", errSubstr: 'IPv4-mapped IPv6 address'}")
  @TestParameters(
      "{expr: \"cidr('10.0.0.0/8').containsIP('not-an-ip')\", errSubstr: 'parse error'}")
  @TestParameters(
      "{expr: \"cidr('10.0.0.0/8').containsCIDR('not-a-cidr')\", errSubstr: 'parse error'}")
  @TestParameters("{expr: \"ip('999.999.999.999')\", errSubstr: '>255'}")
  @TestParameters("{expr: \"ip('fe80::1%')\", errSubstr: 'zone must be non-empty'}")
  @TestParameters("{expr: \"cidr('2001:db8::/')\", errSubstr: 'empty prefix'}")
  @TestParameters("{expr: \"cidr('1.2.3.4')\", errSubstr: \"no '/'\"}")
  @TestParameters("{expr: \"cidr('not-an-ip/24')\", errSubstr: 'unable to parse IP'}")
  @TestParameters("{expr: \"cidr('10.0.0.0/1000')\", errSubstr: 'out of range'}")
  @TestParameters("{expr: \"cidr('10.0.0.0/2a')\", errSubstr: 'invalid prefix length'}")
  @TestParameters("{expr: \"cidr('10.0.0.0/8/16')\", errSubstr: 'unexpected character'}")
  @TestParameters("{expr: \"cidr('fe80::1/64%en0')\", errSubstr: 'out of range'}")
  public void runtimeErrors_throwsEvaluationException(String expr, String errSubstr) {
    CelEvaluationException e = assertThrows(CelEvaluationException.class, () -> eval(expr));
    assertThat(e.getErrorCode()).isEqualTo(CelErrorCode.BAD_FORMAT);
    assertThat(e).hasMessageThat().contains(errSubstr);
    assertThat(e).hasCauseThat().isInstanceOf(CelBadFormatException.class);
    int firstIdx = e.getMessage().indexOf("parse error during conversion from string:");
    if (firstIdx >= 0) {
      assertThat(e.getMessage().indexOf("parse error during conversion from string:", firstIdx + 1))
          .isEqualTo(-1);
    }
  }

  @Test
  public void compileErrors_invalidTypes_throwsValidationException() {
    CelValidationException e =
        assertThrows(
            CelValidationException.class,
            () -> cel.compile("isIP(cidr('192.168.0.0/24'))").getAst());
    assertThat(e)
        .hasMessageThat()
        .contains("found no matching overload for 'isIP' applied to '(net.CIDR)'");

    assertThrows(CelValidationException.class, () -> cel.compile("ip(123)").getAst());
  }

  @Test
  public void boundOpaqueVariables_andDirectOpaqueReturn_success() throws Exception {
    Cel customCel =
        runtimeFlavor
            .builder()
            .addCompilerLibraries(CelExtensions.network())
            .addRuntimeLibraries(CelExtensions.network())
            .addVar("addr", CelIp.IP_TYPE)
            .addVar("subnet", CelCidr.CIDR_TYPE)
            .build();
    ImmutableMap<String, Object> vars =
        ImmutableMap.of(
            "addr", CelIp.parse("10.1.2.3"),
            "subnet", CelCidr.parse("10.0.0.0/8"));

    assertThat(eval(customCel, "subnet.containsIP(addr) && type(addr) == net.IP", vars))
        .isEqualTo(true);
    assertThat(eval(customCel, "ip('10.1.2.3')", vars)).isEqualTo(CelIp.parse("10.1.2.3"));
    assertThat(eval(customCel, "cidr('10.0.0.0/8')", vars)).isEqualTo(CelCidr.parse("10.0.0.0/8"));
    assertThat(eval(customCel, "net.IP", vars)).isEqualTo(TypeType.create(CelIp.IP_TYPE));
    assertThat(eval(customCel, "net.CIDR", vars)).isEqualTo(TypeType.create(CelCidr.CIDR_TYPE));
  }

  @Test
  public void separateLibraryAndLiteRuntime_checkedAndParseOnly_success() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addLibraries(CelNetworkCompilerLibrary.network())
            .build();
    CelLiteRuntime liteRuntime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .setStandardFunctions(CelStandardFunctions.ALL_STANDARD_FUNCTIONS)
            .addLibraries(CelNetworkRuntimeLibrary.network())
            .build();

    String expr =
        "type(ip('192.168.0.1')) == net.IP && type(cidr('192.168.0.0/24')) == net.CIDR &&"
            + " string(cidr('10.0.0.1/8').masked()) == '10.0.0.0/8'";
    CelAbstractSyntaxTree ast =
        isParseOnly ? compiler.parse(expr).getAst() : compiler.compile(expr).getAst();

    Object result = liteRuntime.createProgram(ast).eval();

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void separateLibraryAndStandardRuntime_versioned_success() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addLibraries(CelNetworkCompilerLibrary.network(0))
            .build();
    CelRuntime runtime =
        CelRuntimeFactory.standardCelRuntimeBuilder()
            .addFunctionBindings(CelNetworkRuntimeLibrary.network(0).newFunctionBindings())
            .build();

    CelAbstractSyntaxTree ast =
        compiler.compile("cidr('192.168.1.5/24').masked().isMask()").getAst();
    Object result = runtime.createProgram(ast).eval();

    assertThat(result).isEqualTo(true);
  }

  @Test
  public void separateLibraryAndRuntime_subsetOfFunctions_success() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addLibraries(
                CelNetworkCompilerLibrary.network(CelNetworkCompilerLibrary.Function.IS_IP))
            .build();
    CelRuntime runtime =
        CelRuntimeFactory.standardCelRuntimeBuilder()
            .addFunctionBindings(
                CelNetworkRuntimeLibrary.network(CelNetworkRuntimeLibrary.Function.IS_IP)
                    .newFunctionBindings())
            .build();

    CelAbstractSyntaxTree ast = compiler.compile("isIP('127.0.0.1')").getAst();
    assertThat(runtime.createProgram(ast).eval()).isEqualTo(true);
    assertThrows(
        CelValidationException.class, () -> compiler.compile("isCIDR('10.0.0.0/8')").getAst());
  }

  @Test
  public void separateLibraryAndRuntime_setOfFunctions_success() throws Exception {
    CelCompiler compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addLibraries(
                CelNetworkCompilerLibrary.network(
                    ImmutableSet.of(CelNetworkCompilerLibrary.Function.IS_IP)))
            .build();
    CelLiteRuntime liteRuntime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .addLibraries(
                CelNetworkRuntimeLibrary.network(
                    ImmutableSet.of(CelNetworkRuntimeLibrary.Function.IS_IP)))
            .build();

    CelAbstractSyntaxTree ast = compiler.compile("isIP('127.0.0.1')").getAst();
    assertThat(liteRuntime.createProgram(ast).eval()).isEqualTo(true);
  }

  @Test
  public void network_subsetAndSetAndVersioned_success() throws Exception {
    Cel subsetCel =
        CelFactory.plannerCelBuilder()
            .addCompilerLibraries(
                CelExtensions.network(
                    CelNetworkExtensions.Function.IP, CelNetworkExtensions.Function.STRING))
            .addRuntimeLibraries(
                CelExtensions.network(
                    CelNetworkExtensions.Function.IP, CelNetworkExtensions.Function.STRING))
            .build();
    assertThat(eval(subsetCel, "string(ip('127.0.0.1'))")).isEqualTo("127.0.0.1");
    assertThrows(
        CelValidationException.class, () -> subsetCel.compile("ip('127.0.0.1').family()").getAst());

    Cel setCel =
        CelFactory.plannerCelBuilder()
            .addCompilerLibraries(
                CelExtensions.network(ImmutableSet.of(CelNetworkExtensions.Function.IS_CIDR)))
            .addRuntimeLibraries(
                CelExtensions.network(ImmutableSet.of(CelNetworkExtensions.Function.IS_CIDR)))
            .build();
    assertThat(eval(setCel, "isCIDR('10.0.0.0/8')")).isEqualTo(true);

    Cel v1Cel =
        CelFactory.plannerCelBuilder()
            .addCompilerLibraries(CelExtensions.network(1))
            .addRuntimeLibraries(CelExtensions.network(1))
            .build();
    assertThat(eval(v1Cel, "isIP('::1')")).isEqualTo(true);
  }

  @Test
  public void unsupportedVersion_throws() {
    assertThrows(IllegalArgumentException.class, () -> CelExtensions.network(99));
    assertThrows(IllegalArgumentException.class, () -> CelNetworkCompilerLibrary.network(99));
    assertThrows(IllegalArgumentException.class, () -> CelNetworkRuntimeLibrary.network(99));
  }

  @Test
  public void noArgConstructor_andNullParsing_checks() {
    CelNetworkExtensions extensions = new CelNetworkExtensions();
    assertThat(extensions.version()).isEqualTo(1);
    assertThat(extensions.functions()).hasSize(17);
    assertThat(extensions.variables()).hasSize(2);
    assertThat(extensions.macros()).isEmpty();

    assertThrows(NullPointerException.class, () -> CelIp.parse(null));
    assertThrows(NullPointerException.class, () -> CelCidr.parse(null));
  }
}
