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

/** Immutable CEL opaque value representing an IPv4 or IPv6 address ({@code net.IP}). */
@Immutable
@CheckReturnValue
public final class CelIp extends OpaqueValue {

  /** CEL opaque type for {@code net.IP}. */
  public static final OpaqueType IP_TYPE = OpaqueType.create("net.IP");

  static final byte IPV4_FAMILY = 4;
  static final byte IPV6_FAMILY = 6;
  static final int IPV4_BITS = 32;
  static final int IPV6_BITS = 128;

  private static final int IPV6_HEXTET_COUNT = 8;
  private static final int MAX_IPV6_STRING_LENGTH = 39;

  private final byte family;
  private final long hi;
  private final long lo;

  private CelIp(byte family, long hi, long lo) {
    this.family = family;
    this.hi = hi;
    this.lo = lo;
  }

  /**
   * Parses an ASCII IPv4 dotted-quad or RFC 4291 IPv6 address string.
   *
   * <p>Leading zeros in IPv4 octets, IPv6 zone identifiers ({@code %zone}), and IPv4-mapped IPv6
   * addresses ({@code ::ffff:x.x.x.x}) are rejected, matching CEL {@code net.IP} and Go {@code
   * netip.ParseAddr} semantics.
   *
   * @throws IllegalArgumentException if {@code raw} is not a valid IPv4 or IPv6 literal, contains a
   *     zone identifier, or is an IPv4-mapped IPv6 address
   */
  public static CelIp parse(String raw) {
    ParsedIp parsed = parseWithOptionalZone(raw, "IP Address", raw);
    if (parsed.zone != null) {
      throw new IllegalArgumentException("IP address '" + raw + "' with zone value is not allowed");
    }
    if (parsed.ip.is4In6()) {
      throw new IllegalArgumentException("IPv4-mapped IPv6 address '" + raw + "' is not allowed");
    }
    return parsed.ip;
  }

  static ParsedIp parseWithOptionalZone(String raw, String errorPrefix, String rawForError) {
    if (raw.indexOf(':') >= 0) {
      return parseIpv6WithOptionalZone(raw, errorPrefix, rawForError);
    }
    if (raw.indexOf('.') >= 0) {
      long ipv4 = parseIpv4(raw, errorPrefix, rawForError);
      return new ParsedIp(new CelIp(IPV4_FAMILY, 0L, ipv4), null);
    }
    throw parseError(errorPrefix, rawForError, "unable to parse IP");
  }

  private static ParsedIp parseIpv6WithOptionalZone(
      String raw, String errorPrefix, String rawForError) {
    String ipPart = raw;
    String zone = null;
    int pctIdx = raw.indexOf('%');
    if (pctIdx >= 0) {
      ipPart = raw.substring(0, pctIdx);
      zone = raw.substring(pctIdx + 1);
      if (zone.isEmpty()) {
        throw parseError(errorPrefix, rawForError, "zone must be non-empty");
      }
    }
    CelIp ip = parseIpv6Body(ipPart, errorPrefix, rawForError);
    return new ParsedIp(ip, zone);
  }

  private static long parseIpv4(String s, String errorPrefix, String rawForError) {
    int octet = 0;
    int digits = 0;
    int dotCount = 0;
    long addr = 0;
    int len = s.length();
    for (int i = 0; i < len; i++) {
      char c = s.charAt(i);
      if (c >= '0' && c <= '9') {
        if (digits == 1 && octet == 0) {
          throw parseError(errorPrefix, rawForError, "IPv4 field has octet with leading zero");
        }
        octet = octet * 10 + (c - '0');
        digits++;
        if (octet > 255) {
          throw parseError(errorPrefix, rawForError, "IPv4 field has value >255");
        }
      } else if (c == '.') {
        if (digits == 0) {
          throw parseError(errorPrefix, rawForError, "IPv4 field must have at least one digit");
        }
        if (dotCount == 3) {
          throw parseError(errorPrefix, rawForError, "IPv4 address too long");
        }
        addr = (addr << 8) | octet;
        octet = 0;
        digits = 0;
        dotCount++;
      } else {
        throw parseError(errorPrefix, rawForError, "unexpected character");
      }
    }
    if (dotCount != 3 || digits == 0) {
      throw parseError(errorPrefix, rawForError, "IPv4 address too short");
    }
    return (addr << 8) | octet;
  }

  private static CelIp parseIpv6Body(String s, String errorPrefix, String rawForError) {
    int len = s.length();
    if (len < 2) {
      throw parseError(errorPrefix, rawForError, "address too short");
    }

    int[] hextets = new int[IPV6_HEXTET_COUNT];
    int count = 0;
    boolean hasEllipsis = false;
    int ellipsisIndex = 0;
    int i = 0;

    if (s.charAt(0) == ':') {
      if (s.charAt(1) != ':') {
        throw parseError(errorPrefix, rawForError, "each colon-separated field must have a digit");
      }
      hasEllipsis = true;
      i = 2;
    }

    while (i < len) {
      int segStart = i;
      int val = 0;
      int hexDigits = 0;
      boolean hitDot = false;

      while (i < len) {
        char c = s.charAt(i);
        int digit;
        if (c >= '0' && c <= '9') {
          digit = c - '0';
        } else if (c >= 'a' && c <= 'f') {
          digit = 10 + (c - 'a');
        } else if (c >= 'A' && c <= 'F') {
          digit = 10 + (c - 'A');
        } else if (c == ':') {
          break;
        } else if (c == '.') {
          hitDot = true;
          break;
        } else {
          throw parseError(errorPrefix, rawForError, "unexpected character");
        }
        if (hexDigits == 4) {
          throw parseError(errorPrefix, rawForError, "IPv6 field has too many digits");
        }
        val = (val << 4) | digit;
        hexDigits++;
        i++;
      }

      if (hitDot) {
        if (segStart == 0) {
          throw parseError(errorPrefix, rawForError, "unexpected IPv4 prefix in IPv6 address");
        }
        if (count > 6 || (hasEllipsis && count >= 6)) {
          throw parseError(errorPrefix, rawForError, "address too long");
        }
        long v4 = parseIpv4(s.substring(segStart), errorPrefix, rawForError);
        hextets[count++] = (int) ((v4 >>> 16) & 0xFFFFL);
        hextets[count++] = (int) (v4 & 0xFFFFL);
        break;
      }

      if (hexDigits == 0) {
        if (hasEllipsis) {
          throw parseError(errorPrefix, rawForError, "multiple :: in IPv6 address");
        }
        hasEllipsis = true;
        ellipsisIndex = count;
        i++;
        continue;
      }

      if (count >= IPV6_HEXTET_COUNT) {
        throw parseError(errorPrefix, rawForError, "address too long");
      }
      hextets[count++] = val;

      if (i == len - 1) {
        throw parseError(errorPrefix, rawForError, "trailing colon");
      }
      i++;
    }

    int[] full;
    if (!hasEllipsis) {
      if (count != IPV6_HEXTET_COUNT) {
        throw parseError(errorPrefix, rawForError, "address too short");
      }
      full = hextets;
    } else {
      if (count >= IPV6_HEXTET_COUNT) {
        throw parseError(errorPrefix, rawForError, "address too long for :: compression");
      }
      int zeros = IPV6_HEXTET_COUNT - count;
      full = new int[IPV6_HEXTET_COUNT];
      System.arraycopy(hextets, 0, full, 0, ellipsisIndex);
      System.arraycopy(hextets, ellipsisIndex, full, ellipsisIndex + zeros, count - ellipsisIndex);
    }

    long hiVal =
        ((long) full[0] << 48)
            | ((long) full[1] << 32)
            | ((long) full[2] << 16)
            | (full[3] & 0xFFFFL);
    long loVal =
        ((long) full[4] << 48)
            | ((long) full[5] << 32)
            | ((long) full[6] << 16)
            | (full[7] & 0xFFFFL);
    return new CelIp(IPV6_FAMILY, hiVal, loVal);
  }

  private static IllegalArgumentException parseError(
      String errorPrefix, String raw, String detail) {
    return new IllegalArgumentException(
        errorPrefix + " '" + raw + "' parse error during conversion from string: " + detail);
  }

  @Override
  public CelIp value() {
    return this;
  }

  @Override
  public OpaqueType celType() {
    return IP_TYPE;
  }

  /** Returns the IP address family: {@code 4} for IPv4 or {@code 6} for IPv6. */
  public long family() {
    return family;
  }

  /** Returns true if the IP address is unspecified ({@code 0.0.0.0} or {@code ::}). */
  public boolean isUnspecified() {
    return hi == 0L && lo == 0L;
  }

  /** Returns true if the IP address is a loopback address ({@code 127.0.0.0/8} or {@code ::1}). */
  public boolean isLoopback() {
    if (family == IPV4_FAMILY) {
      return (lo >>> 24) == 127L;
    }
    return hi == 0L && lo == 1L;
  }

  /**
   * Returns true if the IP address is a link-local unicast address ({@code 169.254.0.0/16} or
   * {@code fe80::/10}).
   */
  public boolean isLinkLocalUnicast() {
    if (family == IPV4_FAMILY) {
      return (lo >>> 24) == 169L && ((lo >>> 16) & 0xFFL) == 254L;
    }
    return (hi >>> 56) == 0xFEL && ((hi >>> 48) & 0xC0L) == 0x80L;
  }

  /**
   * Returns true if the IP address is a link-local multicast address ({@code 224.0.0.0/24} or
   * {@code ffX2::/16}, multicast scope {@code 0x2}).
   */
  public boolean isLinkLocalMulticast() {
    if (family == IPV4_FAMILY) {
      return (lo >>> 24) == 224L && ((lo >>> 16) & 0xFFL) == 0L && ((lo >>> 8) & 0xFFL) == 0L;
    }
    return (hi >>> 56) == 0xFFL && ((hi >>> 48) & 0x0FL) == 0x02L;
  }

  /**
   * Returns true if the IP address is a global unicast address (neither unspecified, loopback,
   * multicast, link-local unicast, nor the IPv4 limited broadcast {@code 255.255.255.255}).
   */
  public boolean isGlobalUnicast() {
    // Exclude IPv4 limited broadcast 255.255.255.255 per Go netip.Addr.IsGlobalUnicast.
    if (family == IPV4_FAMILY && lo == 0xFFFFFFFFL) {
      return false;
    }
    return !isUnspecified() && !isLoopback() && !isMulticast() && !isLinkLocalUnicast();
  }

  /** Returns the canonical string form of this IP address (RFC 5952 for IPv6). */
  public String toCanonicalString() {
    if (family == IPV4_FAMILY) {
      return ((lo >>> 24) & 0xFFL)
          + "."
          + ((lo >>> 16) & 0xFFL)
          + "."
          + ((lo >>> 8) & 0xFFL)
          + "."
          + (lo & 0xFFL);
    }
    int[] hextets = {
      (int) ((hi >>> 48) & 0xFFFFL),
      (int) ((hi >>> 32) & 0xFFFFL),
      (int) ((hi >>> 16) & 0xFFFFL),
      (int) (hi & 0xFFFFL),
      (int) ((lo >>> 48) & 0xFFFFL),
      (int) ((lo >>> 32) & 0xFFFFL),
      (int) ((lo >>> 16) & 0xFFFFL),
      (int) (lo & 0xFFFFL),
    };
    // Initialize [bestStart, bestEnd) to width 1 so strictly '>' only matches runs of >= 2 zero
    // hextets (RFC 5952 section 4.2.2) and breaks ties in favor of the leftmost run (RFC 5952
    // section 4.2.3).
    int bestStart = 0;
    int bestEnd = 1;
    int curLen = 0;
    for (int i = 0; i < IPV6_HEXTET_COUNT; i++) {
      if (hextets[i] == 0) {
        curLen++;
        if (curLen > bestEnd - bestStart) {
          bestStart = i - curLen + 1;
          bestEnd = i + 1;
        }
      } else {
        curLen = 0;
      }
    }

    boolean compress = bestEnd > 1;
    StringBuilder sb = new StringBuilder(MAX_IPV6_STRING_LENGTH);
    for (int i = 0; i < IPV6_HEXTET_COUNT; i++) {
      if (compress && i == bestStart) {
        sb.append("::");
        i = bestEnd - 1;
        continue;
      }
      // Skip leading ':' right after "::" since "::" already includes the separator.
      if (i > 0 && !(compress && i == bestEnd)) {
        sb.append(':');
      }
      sb.append(Integer.toHexString(hextets[i]));
    }
    return sb.toString();
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
    if (!(obj instanceof CelIp)) {
      return false;
    }
    CelIp other = (CelIp) obj;
    return this.family == other.family && this.hi == other.hi && this.lo == other.lo;
  }

  @Override
  public int hashCode() {
    return Objects.hash(family, hi, lo);
  }

  boolean is4In6() {
    return hi == 0L && (lo >>> 32) == 0xFFFFL;
  }

  // Multicast: 224.0.0.0/4 for IPv4 or ff00::/8 for IPv6.
  private boolean isMulticast() {
    if (family == IPV4_FAMILY) {
      return ((lo >>> 24) & 0xF0L) == 0xE0L;
    }
    return (hi >>> 56) == 0xFFL;
  }

  static final class ParsedIp {
    final CelIp ip;
    final @Nullable String zone;

    ParsedIp(CelIp ip, @Nullable String zone) {
      this.ip = ip;
      this.zone = zone;
    }
  }
}
