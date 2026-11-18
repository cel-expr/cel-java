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

import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.Immutable;
import dev.cel.common.types.OpaqueType;
import dev.cel.common.values.OpaqueValue;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable CEL opaque value representing an IPv4 or IPv6 CIDR prefix ({@code net.CIDR}). */
@Immutable
@CheckReturnValue
public final class CelCidr extends OpaqueValue {

  /** CEL opaque type for {@code net.CIDR}. */
  public static final OpaqueType CIDR_TYPE = OpaqueType.create("net.CIDR");

  private final CelIp ip;
  private final int prefixLength;

  private CelCidr(CelIp ip, int prefixLength) {
    this.ip = ip;
    this.prefixLength = prefixLength;
  }

  /**
   * Parses a CIDR string strictly according to Go {@code netip.ParsePrefix} semantics, preserving
   * host bits and disallowing zone identifiers and IPv4-mapped IPv6 addresses.
   */
  public static CelCidr parse(String raw) {
    Objects.requireNonNull(raw, "raw");
    int slash = raw.lastIndexOf('/');
    if (slash < 0) {
      throw new IllegalArgumentException(
          "CIDR '" + raw + "' parse error during conversion from string: no '/'");
    }
    String ipPart = raw.substring(0, slash);
    String prefixPart = raw.substring(slash + 1);

    CelIp.ParsedIp parsed = CelIp.parseWithOptionalZone(ipPart, "CIDR", raw);
    if (parsed.zone != null) {
      throw new IllegalArgumentException("CIDR '" + raw + "' with zone value is not allowed");
    }
    if (parsed.ip.is4In6()) {
      throw new IllegalArgumentException("IPv4-mapped IPv6 address '" + raw + "' is not allowed");
    }

    int maxBits = parsed.ip.family() == 4L ? 32 : 128;
    int prefixLen = parsePrefixLength(prefixPart, maxBits, raw);
    return new CelCidr(parsed.ip, prefixLen);
  }

  private static int parsePrefixLength(String prefixPart, int maxBits, String rawForError) {
    if (prefixPart.isEmpty()) {
      throw new IllegalArgumentException(
          "CIDR '" + rawForError + "' parse error during conversion from string: empty prefix");
    }
    if (prefixPart.length() > 1 && prefixPart.charAt(0) == '0') {
      throw new IllegalArgumentException(
          "CIDR '"
              + rawForError
              + "' parse error during conversion from string: prefix length has leading zero");
    }
    if (prefixPart.length() > 3) {
      throw new IllegalArgumentException(
          "CIDR '"
              + rawForError
              + "' parse error during conversion from string: prefix length out of range");
    }
    int value = 0;
    for (int i = 0; i < prefixPart.length(); i++) {
      char c = prefixPart.charAt(i);
      if (c < '0' || c > '9') {
        throw new IllegalArgumentException(
            "CIDR '"
                + rawForError
                + "' parse error during conversion from string: invalid prefix length");
      }
      value = value * 10 + (c - '0');
    }
    if (value > maxBits) {
      throw new IllegalArgumentException(
          "CIDR '"
              + rawForError
              + "' parse error during conversion from string: prefix length out of range");
    }
    return value;
  }

  @Override
  public CelCidr value() {
    return this;
  }

  @Override
  public OpaqueType celType() {
    return CIDR_TYPE;
  }

  /** Returns the underlying {@link CelIp} address, preserving any unmasked host bits. */
  public CelIp ip() {
    return ip;
  }

  /** Returns the CIDR prefix length in bits. */
  public long prefixLength() {
    return prefixLength;
  }

  /** Returns a new {@link CelCidr} with host bits beyond {@link #prefixLength()} zeroed out. */
  public CelCidr masked() {
    return new CelCidr(ip.mask(prefixLength), prefixLength);
  }

  /** Returns true iff all host bits beyond {@link #prefixLength()} are zero. */
  public boolean isMask() {
    return ip.equals(ip.mask(prefixLength));
  }

  /** Returns true iff {@code other} belongs to the network masked by this CIDR. */
  public boolean containsIp(CelIp other) {
    Objects.requireNonNull(other, "other");
    return ip.matchesPrefix(other, prefixLength);
  }

  /** Returns true iff this CIDR contains all addresses in {@code child}. */
  public boolean containsCidr(CelCidr child) {
    Objects.requireNonNull(child, "child");
    return this.ip.family() == child.ip.family()
        && this.prefixLength <= child.prefixLength
        && this.containsIp(child.ip);
  }

  /** Returns the canonical string form {@code "<canonical-ip>/<prefixLength>"}. */
  public String toCanonicalString() {
    return ip.toCanonicalString() + "/" + prefixLength;
  }

  @Override
  public String toString() {
    return toCanonicalString();
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof CelCidr)) {
      return false;
    }
    CelCidr other = (CelCidr) obj;
    return this.prefixLength == other.prefixLength && this.ip.equals(other.ip);
  }

  @Override
  public int hashCode() {
    return Objects.hash(ip, prefixLength);
  }
}
