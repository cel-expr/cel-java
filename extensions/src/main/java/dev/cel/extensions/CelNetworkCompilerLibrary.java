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

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.Immutable;
import dev.cel.checker.CelCheckerBuilder;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelVarDecl;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.TypeType;
import dev.cel.compiler.CelCompilerLibrary;
import java.util.Set;

/** Internal implementation of CEL Network compile-time extensions. */
@Immutable
public final class CelNetworkCompilerLibrary
    implements CelCompilerLibrary, CelExtensionLibrary.FeatureSet {

  /** Supported functions for the Network compiler extension. */
  public enum Function {
    CIDR(
        CelFunctionDecl.newFunctionDeclaration(
            "cidr",
            CelOverloadDecl.newGlobalOverload(
                "string_to_cidr",
                "Parses a string into a CIDR block.",
                CelCidr.CIDR_TYPE,
                SimpleType.STRING))),
    STRING(
        CelFunctionDecl.newFunctionDeclaration(
            "string",
            CelOverloadDecl.newGlobalOverload(
                "ip_to_string",
                "Converts an IP address to its canonical string representation.",
                SimpleType.STRING,
                CelIp.IP_TYPE),
            CelOverloadDecl.newGlobalOverload(
                "cidr_to_string",
                "Converts a CIDR block to its string representation.",
                SimpleType.STRING,
                CelCidr.CIDR_TYPE))),
    CONTAINS_CIDR(
        CelFunctionDecl.newFunctionDeclaration(
            "containsCIDR",
            CelOverloadDecl.newMemberOverload(
                "cidr_contains_cidr",
                "Checks if a CIDR block contains another CIDR block.",
                SimpleType.BOOL,
                CelCidr.CIDR_TYPE,
                CelCidr.CIDR_TYPE),
            CelOverloadDecl.newMemberOverload(
                "cidr_contains_cidr_string",
                "Checks if a CIDR block contains a CIDR block parsed from string.",
                SimpleType.BOOL,
                CelCidr.CIDR_TYPE,
                SimpleType.STRING))),
    CONTAINS_IP(
        CelFunctionDecl.newFunctionDeclaration(
            "containsIP",
            CelOverloadDecl.newMemberOverload(
                "cidr_contains_ip_ip",
                "Checks if a CIDR block contains an IP address.",
                SimpleType.BOOL,
                CelCidr.CIDR_TYPE,
                CelIp.IP_TYPE),
            CelOverloadDecl.newMemberOverload(
                "cidr_contains_ip_string",
                "Checks if a CIDR block contains an IP address parsed from string.",
                SimpleType.BOOL,
                CelCidr.CIDR_TYPE,
                SimpleType.STRING))),
    FAMILY(
        CelFunctionDecl.newFunctionDeclaration(
            "family",
            CelOverloadDecl.newMemberOverload(
                "ip_family",
                "Returns the IP address family (4 or 6).",
                SimpleType.INT,
                CelIp.IP_TYPE))),
    IP(
        CelFunctionDecl.newFunctionDeclaration(
            "ip",
            CelOverloadDecl.newGlobalOverload(
                "string_to_ip",
                "Parses a string into an IP address.",
                CelIp.IP_TYPE,
                SimpleType.STRING),
            CelOverloadDecl.newMemberOverload(
                "cidr_ip",
                "Returns the IP address of the CIDR block, preserving host bits.",
                CelIp.IP_TYPE,
                CelCidr.CIDR_TYPE))),
    IP_IS_CANONICAL(
        CelFunctionDecl.newFunctionDeclaration(
            "ip.isCanonical",
            CelOverloadDecl.newGlobalOverload(
                "ip_is_canonical",
                "Checks whether an IP address string is in canonical RFC 5952 format.",
                SimpleType.BOOL,
                SimpleType.STRING))),
    IS_CIDR(
        CelFunctionDecl.newFunctionDeclaration(
            "isCIDR",
            CelOverloadDecl.newGlobalOverload(
                "is_cidr",
                "Checks if a string is a valid CIDR block.",
                SimpleType.BOOL,
                SimpleType.STRING))),
    IS_GLOBAL_UNICAST(
        CelFunctionDecl.newFunctionDeclaration(
            "isGlobalUnicast",
            CelOverloadDecl.newMemberOverload(
                "ip_is_global_unicast",
                "Checks if the IP address is a global unicast address.",
                SimpleType.BOOL,
                CelIp.IP_TYPE))),
    IS_IP(
        CelFunctionDecl.newFunctionDeclaration(
            "isIP",
            CelOverloadDecl.newGlobalOverload(
                "is_ip",
                "Checks if a string is a valid IP address.",
                SimpleType.BOOL,
                SimpleType.STRING))),
    IS_LINK_LOCAL_MULTICAST(
        CelFunctionDecl.newFunctionDeclaration(
            "isLinkLocalMulticast",
            CelOverloadDecl.newMemberOverload(
                "ip_is_link_local_multicast",
                "Checks if the IP address is a link-local multicast address.",
                SimpleType.BOOL,
                CelIp.IP_TYPE))),
    IS_LINK_LOCAL_UNICAST(
        CelFunctionDecl.newFunctionDeclaration(
            "isLinkLocalUnicast",
            CelOverloadDecl.newMemberOverload(
                "ip_is_link_local_unicast",
                "Checks if the IP address is a link-local unicast address.",
                SimpleType.BOOL,
                CelIp.IP_TYPE))),
    IS_LOOPBACK(
        CelFunctionDecl.newFunctionDeclaration(
            "isLoopback",
            CelOverloadDecl.newMemberOverload(
                "ip_is_loopback",
                "Checks if the IP address is a loopback address.",
                SimpleType.BOOL,
                CelIp.IP_TYPE))),
    IS_MASK(
        CelFunctionDecl.newFunctionDeclaration(
            "isMask",
            CelOverloadDecl.newMemberOverload(
                "cidr_is_mask",
                "Checks if the CIDR block has all host bits set to zero.",
                SimpleType.BOOL,
                CelCidr.CIDR_TYPE))),
    IS_UNSPECIFIED(
        CelFunctionDecl.newFunctionDeclaration(
            "isUnspecified",
            CelOverloadDecl.newMemberOverload(
                "ip_is_unspecified",
                "Checks if the IP address is unspecified (0.0.0.0 or ::).",
                SimpleType.BOOL,
                CelIp.IP_TYPE))),
    MASKED(
        CelFunctionDecl.newFunctionDeclaration(
            "masked",
            CelOverloadDecl.newMemberOverload(
                "cidr_masked",
                "Returns the CIDR block with host bits zeroed out.",
                CelCidr.CIDR_TYPE,
                CelCidr.CIDR_TYPE))),
    PREFIX_LENGTH(
        CelFunctionDecl.newFunctionDeclaration(
            "prefixLength",
            CelOverloadDecl.newMemberOverload(
                "cidr_prefix_length",
                "Returns the prefix length of the CIDR block.",
                SimpleType.INT,
                CelCidr.CIDR_TYPE)));

    private final CelFunctionDecl functionDecl;

    String getFunction() {
      return functionDecl.name();
    }

    public CelFunctionDecl getFunctionDecl() {
      return functionDecl;
    }

    Function(CelFunctionDecl functionDecl) {
      this.functionDecl = functionDecl;
    }
  }

  private static final ImmutableSet<CelVarDecl> NETWORK_TYPE_VARIABLES =
      ImmutableSet.of(
          CelVarDecl.newVarDeclaration(CelIp.IP_TYPE.name(), TypeType.create(CelIp.IP_TYPE)),
          CelVarDecl.newVarDeclaration(
              CelCidr.CIDR_TYPE.name(), TypeType.create(CelCidr.CIDR_TYPE)));

  private static final class Library implements CelExtensionLibrary<CelNetworkCompilerLibrary> {
    private final CelNetworkCompilerLibrary version0 =
        new CelNetworkCompilerLibrary(
            0, ImmutableSet.copyOf(Function.values()), NETWORK_TYPE_VARIABLES);
    private final CelNetworkCompilerLibrary version1 =
        new CelNetworkCompilerLibrary(
            1, ImmutableSet.copyOf(Function.values()), NETWORK_TYPE_VARIABLES);

    @Override
    public String name() {
      return "network";
    }

    @Override
    public ImmutableSet<CelNetworkCompilerLibrary> versions() {
      return ImmutableSet.of(version0, version1);
    }
  }

  private static final Library LIBRARY = new Library();

  /** Returns the {@link CelExtensionLibrary} descriptor for the 'network' compiler extension. */
  public static CelExtensionLibrary<CelNetworkCompilerLibrary> library() {
    return LIBRARY;
  }

  /** Returns the latest version of the 'network' compiler extension. */
  public static CelNetworkCompilerLibrary network() {
    return LIBRARY.version1;
  }

  /** Returns the specified version of the 'network' compiler extension. */
  public static CelNetworkCompilerLibrary network(int version) {
    return library().version(version);
  }

  /** Returns the 'network' compiler extension with only the specified functions. */
  public static CelNetworkCompilerLibrary network(Function... functions) {
    return network(ImmutableSet.copyOf(functions));
  }

  /** Returns the 'network' compiler extension with only the specified functions. */
  public static CelNetworkCompilerLibrary network(Set<Function> functions) {
    return new CelNetworkCompilerLibrary(functions);
  }

  private final ImmutableSet<Function> functions;
  private final ImmutableSet<CelVarDecl> variables;
  private final int version;

  CelNetworkCompilerLibrary(Set<Function> functions) {
    this(-1, functions, NETWORK_TYPE_VARIABLES);
  }

  private CelNetworkCompilerLibrary(
      int version, Set<Function> functions, Set<CelVarDecl> variables) {
    this.version = version;
    this.functions = ImmutableSet.copyOf(functions);
    this.variables = ImmutableSet.copyOf(variables);
  }

  @Override
  public int version() {
    return version;
  }

  @Override
  public ImmutableSet<CelFunctionDecl> functions() {
    return functions.stream().map(Function::getFunctionDecl).collect(toImmutableSet());
  }

  @Override
  public ImmutableSet<CelVarDecl> variables() {
    return variables;
  }

  @Override
  public void setCheckerOptions(CelCheckerBuilder checkerBuilder) {
    checkerBuilder.addVarDeclarations(variables());
    functions.forEach(function -> checkerBuilder.addFunctionDeclarations(function.functionDecl));
  }
}
