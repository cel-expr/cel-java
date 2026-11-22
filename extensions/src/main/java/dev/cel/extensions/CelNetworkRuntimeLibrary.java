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

import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.exceptions.CelBadFormatException;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelLiteRuntimeBuilder;
import dev.cel.runtime.CelLiteRuntimeLibrary;
import java.util.Set;

/** Runtime implementation of CEL Network extension functions. */
@Immutable
public final class CelNetworkRuntimeLibrary implements CelLiteRuntimeLibrary {

  /** Enumeration of runtime function bindings for the Network extension. */
  public enum Function {
    CIDR(
        "cidr",
        ImmutableSet.of(
            CelFunctionBinding.from(
                "string_to_cidr", String.class, CelNetworkRuntimeLibrary::parseCidr))),
    STRING(
        "string",
        ImmutableSet.of(
            CelFunctionBinding.from("ip_to_string", CelIp.class, CelIp::toCanonicalString),
            CelFunctionBinding.from("cidr_to_string", CelCidr.class, CelCidr::toCanonicalString))),
    CONTAINS_CIDR(
        "containsCIDR",
        ImmutableSet.of(
            CelFunctionBinding.from(
                "cidr_contains_cidr", CelCidr.class, CelCidr.class, CelCidr::containsCidr),
            CelFunctionBinding.from(
                "cidr_contains_cidr_string",
                CelCidr.class,
                String.class,
                (parent, childStr) -> parent.containsCidr(parseCidr(childStr))))),
    CONTAINS_IP(
        "containsIP",
        ImmutableSet.of(
            CelFunctionBinding.from(
                "cidr_contains_ip_ip", CelCidr.class, CelIp.class, CelCidr::containsIp),
            CelFunctionBinding.from(
                "cidr_contains_ip_string",
                CelCidr.class,
                String.class,
                (cidr, ipStr) -> cidr.containsIp(parseIp(ipStr))))),
    FAMILY(
        "family",
        ImmutableSet.of(CelFunctionBinding.from("ip_family", CelIp.class, CelIp::family))),
    IP(
        "ip",
        ImmutableSet.of(
            CelFunctionBinding.from(
                "string_to_ip", String.class, CelNetworkRuntimeLibrary::parseIp),
            CelFunctionBinding.from("cidr_ip", CelCidr.class, CelCidr::ip))),
    IP_IS_CANONICAL(
        "ip.isCanonical",
        ImmutableSet.of(
            CelFunctionBinding.from(
                "ip_is_canonical", String.class, CelNetworkRuntimeLibrary::isCanonical))),
    IS_CIDR(
        "isCIDR",
        ImmutableSet.of(
            CelFunctionBinding.from("is_cidr", String.class, CelNetworkRuntimeLibrary::isCidr))),
    IS_GLOBAL_UNICAST(
        "isGlobalUnicast",
        ImmutableSet.of(
            CelFunctionBinding.from("ip_is_global_unicast", CelIp.class, CelIp::isGlobalUnicast))),
    IS_IP(
        "isIP",
        ImmutableSet.of(
            CelFunctionBinding.from("is_ip", String.class, CelNetworkRuntimeLibrary::isIp))),
    IS_LINK_LOCAL_MULTICAST(
        "isLinkLocalMulticast",
        ImmutableSet.of(
            CelFunctionBinding.from(
                "ip_is_link_local_multicast", CelIp.class, CelIp::isLinkLocalMulticast))),
    IS_LINK_LOCAL_UNICAST(
        "isLinkLocalUnicast",
        ImmutableSet.of(
            CelFunctionBinding.from(
                "ip_is_link_local_unicast", CelIp.class, CelIp::isLinkLocalUnicast))),
    IS_LOOPBACK(
        "isLoopback",
        ImmutableSet.of(CelFunctionBinding.from("ip_is_loopback", CelIp.class, CelIp::isLoopback))),
    IS_MASK(
        "isMask",
        ImmutableSet.of(CelFunctionBinding.from("cidr_is_mask", CelCidr.class, CelCidr::isMask))),
    IS_UNSPECIFIED(
        "isUnspecified",
        ImmutableSet.of(
            CelFunctionBinding.from("ip_is_unspecified", CelIp.class, CelIp::isUnspecified))),
    MASKED(
        "masked",
        ImmutableSet.of(CelFunctionBinding.from("cidr_masked", CelCidr.class, CelCidr::masked))),
    PREFIX_LENGTH(
        "prefixLength",
        ImmutableSet.of(
            CelFunctionBinding.from("cidr_prefix_length", CelCidr.class, CelCidr::prefixLength)));

    private final String functionName;
    private final ImmutableSet<CelFunctionBinding> functionBindings;

    Function(String functionName, ImmutableSet<CelFunctionBinding> functionBindings) {
      this.functionName = functionName;
      this.functionBindings = functionBindings;
    }
  }

  private static final CelNetworkRuntimeLibrary VERSION_1 =
      new CelNetworkRuntimeLibrary(ImmutableSet.copyOf(Function.values()));

  /** Returns the latest version of the 'network' runtime functions. */
  public static CelNetworkRuntimeLibrary network() {
    return VERSION_1;
  }

  /** Returns the specified version of the 'network' runtime functions. */
  public static CelNetworkRuntimeLibrary network(int version) {
    switch (version) {
      case 0:
      case 1:
      case Integer.MAX_VALUE:
        return VERSION_1;
      default:
        throw new IllegalArgumentException("Unsupported 'network' extension version " + version);
    }
  }

  /** Returns the 'network' runtime functions with only the specified functions. */
  public static CelNetworkRuntimeLibrary network(Function... functions) {
    return network(ImmutableSet.copyOf(functions));
  }

  /** Returns the 'network' runtime functions with only the specified functions. */
  public static CelNetworkRuntimeLibrary network(Set<Function> functions) {
    return new CelNetworkRuntimeLibrary(functions);
  }

  private final ImmutableSet<Function> functions;

  CelNetworkRuntimeLibrary(Set<Function> functions) {
    this.functions = ImmutableSet.copyOf(functions);
  }

  @Override
  public void setRuntimeOptions(CelLiteRuntimeBuilder runtimeBuilder) {
    runtimeBuilder.addFunctionBindings(newFunctionBindings());
  }

  /** Creates the {@link CelFunctionBinding}s for the configured network functions. */
  public ImmutableSet<CelFunctionBinding> newFunctionBindings() {
    ImmutableSet.Builder<CelFunctionBinding> builder = ImmutableSet.builder();
    for (Function function : functions) {
      builder.addAll(
          CelFunctionBinding.fromOverloads(function.functionName, function.functionBindings));
    }
    return builder.build();
  }

  private static CelIp parseIp(String raw) {
    try {
      return CelIp.parse(raw);
    } catch (IllegalArgumentException e) {
      throw new CelBadFormatException(e);
    }
  }

  private static CelCidr parseCidr(String raw) {
    try {
      return CelCidr.parse(raw);
    } catch (IllegalArgumentException e) {
      throw new CelBadFormatException(e);
    }
  }

  private static boolean isCanonical(String raw) {
    return parseIp(raw).toCanonicalString().equals(raw);
  }

  private static boolean isIp(String raw) {
    try {
      CelIp unused = CelIp.parse(raw);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static boolean isCidr(String raw) {
    try {
      CelCidr unused = CelCidr.parse(raw);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
