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
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelErrorCode;
import dev.cel.common.CelFunctionDecl;
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
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class CelNetworkExtensionsTest extends CelExtensionTestBase {

  @Override
  protected Cel newCelEnv() {
    return runtimeFlavor
        .builder()
        .addCompilerLibraries(CelNetworkCompilerLibrary.network())
        .addFunctionBindings(CelNetworkRuntimeLibrary.network().newFunctionBindings())
        .build();
  }

  @Test
  public void library() {
    CelExtensionLibrary<CelNetworkCompilerLibrary> library = CelNetworkCompilerLibrary.library();
    assertThat(library.name()).isEqualTo("network");
    assertThat(library.latest().version()).isEqualTo(1);
    assertThat(library.version(0).version()).isEqualTo(0);
    assertThat(library.version(0).functions().stream().map(CelFunctionDecl::name))
        .containsExactly(
            "string",
            "family",
            "ip",
            "ip.isCanonical",
            "isGlobalUnicast",
            "isIP",
            "isLinkLocalMulticast",
            "isLinkLocalUnicast",
            "isLoopback",
            "isUnspecified");
    assertThat(library.version(1).variables().stream().map(CelVarDecl::name))
        .containsExactly("net.IP");
    assertThat(library.version(1).macros()).isEmpty();
  }

  @Test
  @TestParameters("{expr: \"ip('127.0.0.1') == ip('127.0.0.1')\"}")
  @TestParameters("{expr: \"ip('2001:db8::1') == ip('2001:DB8::1')\"}")
  @TestParameters("{expr: \"ip('127.0.0.1') != ip('1.2.3.4')\"}")
  @TestParameters("{expr: \"ip('192.168.0.1') != ip('2001:db8::1')\"}")
  @TestParameters("{expr: \"ip('0.0.0.0') != ip('::')\"}")
  @TestParameters("{expr: \"ip('0.0.0.1') != ip('::1')\"}")
  @TestParameters("{expr: \"ip('::') != ip('::ffff')\"}")
  @TestParameters("{expr: \"dyn(ip('0.0.0.0')) != dyn(ip('::'))\"}")
  @TestParameters("{expr: \"dyn(ip('10.0.0.1')) != '10.0.0.1'\"}")
  @TestParameters("{expr: \"type(dyn(ip('10.0.0.1'))) == net.IP\"}")
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
  @TestParameters("{expr: \"ip('::ffff:ffff').isGlobalUnicast()\"}")
  @TestParameters("{expr: \"ip('ff02::1').isLinkLocalMulticast()\"}")
  @TestParameters("{expr: \"ip('ff12::1').isLinkLocalMulticast()\"}")
  @TestParameters("{expr: \"!ip('ff05::1').isLinkLocalMulticast()\"}")
  @TestParameters("{expr: \"!ip('fd00::1').isLinkLocalMulticast()\"}")
  @TestParameters("{expr: \"ip('224.0.0.1').isLinkLocalMulticast()\"}")
  @TestParameters("{expr: \"!ip('224.0.1.1').isLinkLocalMulticast()\"}")
  @TestParameters("{expr: \"!ip('224.1.0.1').isLinkLocalMulticast()\"}")
  @TestParameters("{expr: \"ip('169.254.169.254').isLinkLocalUnicast()\"}")
  @TestParameters("{expr: \"!ip('192.168.0.1').isLinkLocalUnicast()\"}")
  @TestParameters("{expr: \"!ip('169.255.0.1').isLinkLocalUnicast()\"}")
  @TestParameters("{expr: \"ip('fe80::1').isLinkLocalUnicast()\"}")
  @TestParameters("{expr: \"!ip('fd80::1').isLinkLocalUnicast()\"}")
  @TestParameters("{expr: \"!ip('fec0::1').isLinkLocalUnicast()\"}")
  @TestParameters("{expr: \"ip('127.0.0.1').isLoopback()\"}")
  @TestParameters("{expr: \"!ip('1.2.3.4').isLoopback()\"}")
  @TestParameters("{expr: \"ip('::1').isLoopback()\"}")
  @TestParameters("{expr: \"!ip('2001:db8::abcd').isLoopback()\"}")
  @TestParameters("{expr: \"ip('0.0.0.0').isUnspecified()\"}")
  @TestParameters("{expr: \"!ip('127.0.0.1').isUnspecified()\"}")
  @TestParameters("{expr: \"ip('::').isUnspecified()\"}")
  @TestParameters("{expr: \"!ip('::1').isUnspecified()\"}")
  @TestParameters("{expr: \"isIP('1.2.3.4')\"}")
  @TestParameters("{expr: \"isIP('2001:db8::1')\"}")
  @TestParameters("{expr: \"isIP('64:ff9b::192.0.2.1')\"}")
  @TestParameters("{expr: \"isIP('::192.168.0.1')\"}")
  @TestParameters("{expr: \"!isIP('')\"}")
  @TestParameters("{expr: \"!isIP('not.an.ip')\"}")
  @TestParameters("{expr: \"!isIP('127.0.0.1:80')\"}")
  @TestParameters("{expr: \"!isIP('01.2.3.4')\"}")
  @TestParameters("{expr: \"!isIP('192.168.001.1')\"}")
  @TestParameters("{expr: \"!isIP('192.168.300.1')\"}")
  @TestParameters("{expr: \"!isIP('192.168.0.1.0')\"}")
  @TestParameters("{expr: \"!isIP('192.168.0.')\"}")
  @TestParameters("{expr: \"!isIP('.192.168.0.1')\"}")
  @TestParameters("{expr: \"!isIP('1.2.3')\"}")
  @TestParameters("{expr: \"!isIP('1..2.3.4')\"}")
  @TestParameters("{expr: \"!isIP(':')\"}")
  @TestParameters("{expr: \"!isIP('fe801::1')\"}")
  @TestParameters("{expr: \"!isIP('gggg::1')\"}")
  @TestParameters("{expr: \"!isIP('gggg::1%en0')\"}")
  @TestParameters("{expr: \"!isIP('fe80::1%')\"}")
  @TestParameters("{expr: \"!isIP('2001:db8:::68')\"}")
  @TestParameters("{expr: \"!isIP('1::2::3')\"}")
  @TestParameters("{expr: \"!isIP('1:2:3:4:5:6:7::8')\"}")
  @TestParameters("{expr: \"!isIP('1:2:3:4:5:6:7:1.2.3.4')\"}")
  @TestParameters("{expr: \"!isIP('1:2:3:4:5:6::1.2.3.4')\"}")
  @TestParameters("{expr: \"!isIP('1:2:3:4:5:6:7:8:9')\"}")
  @TestParameters("{expr: \"!isIP('1:2:3:4:5:6:7')\"}")
  @TestParameters("{expr: \"!isIP('1:2:3:4:5:192.168.0.1')\"}")
  @TestParameters("{expr: \"!isIP('fe80::1%en0')\"}")
  @TestParameters("{expr: \"!isIP('::ffff:192.168.0.1')\"}")
  @TestParameters("{expr: \"!isIP('::ffff:c0a8:1')\"}")
  @TestParameters("{expr: \"type(ip('192.168.0.1')) == net.IP\"}")
  public void networkExpressions_true(String expr) throws Exception {
    assertThat(eval(expr)).isEqualTo(true);
  }

  @Test
  @TestParameters("{expr: \"string(ip('1.2.3.4'))\", expected: '1.2.3.4'}")
  @TestParameters("{expr: \"string(42) + ' ' + string(ip('1.2.3.4'))\", expected: '42 1.2.3.4'}")
  @TestParameters("{expr: \"string(ip('2001:db8::1'))\", expected: '2001:db8::1'}")
  @TestParameters("{expr: \"string(ip('2001:0DB8:0:0:0:0:0:1'))\", expected: '2001:db8::1'}")
  @TestParameters("{expr: \"string(ip('2001:0:0:1:0:0:0:1'))\", expected: '2001:0:0:1::1'}")
  @TestParameters("{expr: \"string(ip('2001:0:0:1:1:0:0:1'))\", expected: '2001::1:1:0:0:1'}")
  @TestParameters(
      "{expr: \"string(ip('2001:db8:0:1:1:1:1:1'))\", expected: '2001:db8:0:1:1:1:1:1'}")
  @TestParameters("{expr: \"string(ip('64:ff9b::192.0.2.1'))\", expected: '64:ff9b::c000:201'}")
  @TestParameters("{expr: \"string(ip('0:0:0:0:0:0:0:0'))\", expected: '::'}")
  public void stringConversion_success(String expr, String expected) throws Exception {
    Assume.assumeFalse(isParseOnly);
    assertThat(eval(expr)).isEqualTo(expected);
  }

  @Test
  @TestParameters("{expr: \"ip('127.0.0.1').family()\", expected: 4}")
  @TestParameters("{expr: \"ip('::1').family()\", expected: 6}")
  public void intReturnFunctions_success(String expr, long expected) throws Exception {
    assertThat(eval(expr)).isEqualTo(expected);
  }

  @Test
  @TestParameters("{expr: \"ip('192.168.0.1.0')\", errSubstr: 'parse error'}")
  @TestParameters("{expr: \"ip('01.2.3.4')\", errSubstr: 'parse error'}")
  @TestParameters("{expr: \"ip('999.999.999.999')\", errSubstr: '>255'}")
  @TestParameters("{expr: \"ip('2001:db8:::68')\", errSubstr: 'parse error'}")
  @TestParameters("{expr: \"ip('2001:db8:')\", errSubstr: 'parse error'}")
  @TestParameters("{expr: \"ip(':2001:db8::1')\", errSubstr: 'parse error'}")
  @TestParameters("{expr: \"ip('192.168.0.1%eth0')\", errSubstr: 'parse error'}")
  @TestParameters("{expr: \"ip('fe80::1%')\", errSubstr: 'zone must be non-empty'}")
  @TestParameters("{expr: \"ip('fe80::1%en0')\", errSubstr: 'with zone value is not allowed'}")
  @TestParameters("{expr: \"ip('::ffff:192.168.0.1')\", errSubstr: 'IPv4-mapped IPv6 address'}")
  @TestParameters("{expr: \"ip('::ffff:c0a8:1')\", errSubstr: 'IPv4-mapped IPv6 address'}")
  @TestParameters("{expr: \"ip('::FFFF:192.168.0.1')\", errSubstr: 'IPv4-mapped IPv6 address'}")
  @TestParameters("{expr: \"ip.isCanonical('127.0.0.1.0')\", errSubstr: 'parse error'}")
  @TestParameters("{expr: \"ip.isCanonical('2001:db8:::68')\", errSubstr: 'parse error'}")
  @TestParameters("{expr: \"ip.isCanonical('fe80::1%en0')\", errSubstr: 'with zone value'}")
  public void runtimeErrors_throwsEvaluationException(String expr, String errSubstr) {
    CelEvaluationException e = assertThrows(CelEvaluationException.class, () -> eval(expr));
    assertThat(e.getErrorCode()).isEqualTo(CelErrorCode.BAD_FORMAT);
    assertThat(e).hasMessageThat().contains(errSubstr);
    assertThat(e).hasCauseThat().isInstanceOf(CelBadFormatException.class);
  }

  @Test
  public void compileErrors_invalidTypes_throwsValidationException() {
    assertThrows(CelValidationException.class, () -> cel.compile("ip(123)").getAst());
  }

  @Test
  public void boundOpaqueVariables_andDirectOpaqueReturn_success() throws Exception {
    Cel customCel =
        runtimeFlavor
            .builder()
            .addCompilerLibraries(CelNetworkCompilerLibrary.network())
            .addFunctionBindings(CelNetworkRuntimeLibrary.network().newFunctionBindings())
            .addVar("addr", CelIp.IP_TYPE)
            .build();
    ImmutableMap<String, Object> vars = ImmutableMap.of("addr", CelIp.parse("10.1.2.3"));

    assertThat(eval(customCel, "addr.family() == 4 && type(addr) == net.IP", vars)).isEqualTo(true);
    assertThat(eval(customCel, "ip('10.1.2.3')", vars)).isEqualTo(CelIp.parse("10.1.2.3"));
    assertThat(eval(customCel, "net.IP", vars)).isEqualTo(TypeType.create(CelIp.IP_TYPE));
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

    String expr = "type(ip('192.168.0.1')) == net.IP && ip('2001:0DB8::1').family() == 6";
    CelAbstractSyntaxTree ast =
        isParseOnly ? compiler.parse(expr).getAst() : compiler.compile(expr).getAst();

    assertThat(liteRuntime.createProgram(ast).eval()).isEqualTo(true);
    assertThat(
            liteRuntime
                .createProgram(compiler.compile("string(ip('2001:0DB8::1'))").getAst())
                .eval())
        .isEqualTo("2001:db8::1");
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

    CelAbstractSyntaxTree ast = compiler.compile("ip('127.0.0.1').isLoopback()").getAst();
    assertThat(runtime.createProgram(ast).eval()).isEqualTo(true);

    CelCompiler v1Compiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addLibraries(CelNetworkCompilerLibrary.network(1))
            .build();
    CelLiteRuntime maxRuntime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .addLibraries(CelNetworkRuntimeLibrary.network(Integer.MAX_VALUE))
            .build();
    assertThat(maxRuntime.createProgram(v1Compiler.compile("isIP('::1')").getAst()).eval())
        .isEqualTo(true);
  }

  @Test
  public void separateLibraryAndRuntime_subsetAndSetOfFunctions_success() throws Exception {
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
    assertThrows(CelValidationException.class, () -> compiler.compile("ip('127.0.0.1')").getAst());

    CelCompiler setCompiler =
        CelCompilerFactory.standardCelCompilerBuilder()
            .addLibraries(
                CelNetworkCompilerLibrary.network(
                    ImmutableSet.of(CelNetworkCompilerLibrary.Function.IS_IP)))
            .build();
    CelLiteRuntime setLiteRuntime =
        CelLiteRuntimeFactory.newLiteRuntimeBuilder()
            .addLibraries(
                CelNetworkRuntimeLibrary.network(
                    ImmutableSet.of(CelNetworkRuntimeLibrary.Function.IS_IP)))
            .build();
    assertThat(
            setLiteRuntime.createProgram(setCompiler.compile("isIP('127.0.0.1')").getAst()).eval())
        .isEqualTo(true);
    assertThat(
            CelNetworkCompilerLibrary.network(
                    ImmutableSet.of(CelNetworkCompilerLibrary.Function.IS_IP))
                .version())
        .isEqualTo(-1);
  }

  @Test
  public void unsupportedVersion_throws() {
    assertThrows(IllegalArgumentException.class, () -> CelNetworkCompilerLibrary.network(99));
    assertThrows(IllegalArgumentException.class, () -> CelNetworkRuntimeLibrary.network(99));
  }
}
