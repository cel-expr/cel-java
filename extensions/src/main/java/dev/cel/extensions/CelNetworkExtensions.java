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
import dev.cel.common.CelVarDecl;
import dev.cel.compiler.CelCompilerLibrary;
import dev.cel.runtime.CelRuntimeBuilder;
import dev.cel.runtime.CelRuntimeLibrary;
import java.util.Set;

/** Internal implementation of CEL Network extensions. */
@Immutable
public final class CelNetworkExtensions
    implements CelCompilerLibrary, CelRuntimeLibrary, CelExtensionLibrary.FeatureSet {

  /** Denotes the network extension function. */
  public enum Function {
    CIDR(CelNetworkCompilerLibrary.Function.CIDR, CelNetworkRuntimeLibrary.Function.CIDR),
    STRING(CelNetworkCompilerLibrary.Function.STRING, CelNetworkRuntimeLibrary.Function.STRING),
    CONTAINS_CIDR(
        CelNetworkCompilerLibrary.Function.CONTAINS_CIDR,
        CelNetworkRuntimeLibrary.Function.CONTAINS_CIDR),
    CONTAINS_IP(
        CelNetworkCompilerLibrary.Function.CONTAINS_IP,
        CelNetworkRuntimeLibrary.Function.CONTAINS_IP),
    FAMILY(CelNetworkCompilerLibrary.Function.FAMILY, CelNetworkRuntimeLibrary.Function.FAMILY),
    IP(CelNetworkCompilerLibrary.Function.IP, CelNetworkRuntimeLibrary.Function.IP),
    IP_IS_CANONICAL(
        CelNetworkCompilerLibrary.Function.IP_IS_CANONICAL,
        CelNetworkRuntimeLibrary.Function.IP_IS_CANONICAL),
    IS_CIDR(CelNetworkCompilerLibrary.Function.IS_CIDR, CelNetworkRuntimeLibrary.Function.IS_CIDR),
    IS_GLOBAL_UNICAST(
        CelNetworkCompilerLibrary.Function.IS_GLOBAL_UNICAST,
        CelNetworkRuntimeLibrary.Function.IS_GLOBAL_UNICAST),
    IS_IP(CelNetworkCompilerLibrary.Function.IS_IP, CelNetworkRuntimeLibrary.Function.IS_IP),
    IS_LINK_LOCAL_MULTICAST(
        CelNetworkCompilerLibrary.Function.IS_LINK_LOCAL_MULTICAST,
        CelNetworkRuntimeLibrary.Function.IS_LINK_LOCAL_MULTICAST),
    IS_LINK_LOCAL_UNICAST(
        CelNetworkCompilerLibrary.Function.IS_LINK_LOCAL_UNICAST,
        CelNetworkRuntimeLibrary.Function.IS_LINK_LOCAL_UNICAST),
    IS_LOOPBACK(
        CelNetworkCompilerLibrary.Function.IS_LOOPBACK,
        CelNetworkRuntimeLibrary.Function.IS_LOOPBACK),
    IS_MASK(CelNetworkCompilerLibrary.Function.IS_MASK, CelNetworkRuntimeLibrary.Function.IS_MASK),
    IS_UNSPECIFIED(
        CelNetworkCompilerLibrary.Function.IS_UNSPECIFIED,
        CelNetworkRuntimeLibrary.Function.IS_UNSPECIFIED),
    MASKED(CelNetworkCompilerLibrary.Function.MASKED, CelNetworkRuntimeLibrary.Function.MASKED),
    PREFIX_LENGTH(
        CelNetworkCompilerLibrary.Function.PREFIX_LENGTH,
        CelNetworkRuntimeLibrary.Function.PREFIX_LENGTH);

    private final CelNetworkCompilerLibrary.Function compilerFunction;
    private final CelNetworkRuntimeLibrary.Function runtimeFunction;

    String getFunction() {
      return compilerFunction.getFunction();
    }

    Function(
        CelNetworkCompilerLibrary.Function compilerFunction,
        CelNetworkRuntimeLibrary.Function runtimeFunction) {
      this.compilerFunction = compilerFunction;
      this.runtimeFunction = runtimeFunction;
    }
  }

  private static final class Library implements CelExtensionLibrary<CelNetworkExtensions> {
    private final ImmutableSet<CelNetworkExtensions> versions;

    Library() {
      versions =
          CelNetworkCompilerLibrary.library().versions().stream()
              .map(CelNetworkExtensions::new)
              .collect(toImmutableSet());
    }

    @Override
    public String name() {
      return CelNetworkCompilerLibrary.library().name();
    }

    @Override
    public ImmutableSet<CelNetworkExtensions> versions() {
      return versions;
    }
  }

  private static final Library LIBRARY = new Library();

  static CelExtensionLibrary<CelNetworkExtensions> library() {
    return LIBRARY;
  }

  private final CelNetworkCompilerLibrary compilerLibrary;
  private final CelNetworkRuntimeLibrary runtimeLibrary;

  CelNetworkExtensions() {
    this(CelNetworkCompilerLibrary.network());
  }

  CelNetworkExtensions(Set<Function> functions) {
    this.compilerLibrary =
        new CelNetworkCompilerLibrary(
            functions.stream().map(f -> f.compilerFunction).collect(toImmutableSet()));
    this.runtimeLibrary =
        new CelNetworkRuntimeLibrary(
            functions.stream().map(f -> f.runtimeFunction).collect(toImmutableSet()));
  }

  private CelNetworkExtensions(CelNetworkCompilerLibrary compilerLibrary) {
    this.compilerLibrary = compilerLibrary;
    this.runtimeLibrary = CelNetworkRuntimeLibrary.network(compilerLibrary.version());
  }

  @Override
  public int version() {
    return compilerLibrary.version();
  }

  @Override
  public ImmutableSet<CelFunctionDecl> functions() {
    return compilerLibrary.functions();
  }

  @Override
  public ImmutableSet<CelVarDecl> variables() {
    return compilerLibrary.variables();
  }

  @Override
  public void setCheckerOptions(CelCheckerBuilder checkerBuilder) {
    compilerLibrary.setCheckerOptions(checkerBuilder);
  }

  @Override
  public void setRuntimeOptions(CelRuntimeBuilder runtimeBuilder) {
    runtimeBuilder.addFunctionBindings(runtimeLibrary.newFunctionBindings());
  }
}
