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

  private final byte family;
  private final long hi;
  private final long lo;

  private CelIp(byte family, long hi, long lo) {
    this.family = family;
    this.hi = hi;
    this.lo = lo;
  }

  /**
   * Parses an IPv4 or IPv6 address string strictly according to Go {@code netip.ParseAddr} / RFC
   * 4291 semantics, disallowing zone identifiers and IPv4-mapped IPv6 addresses.
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
    if (raw.isEmpty()) {
      throw parseError(errorPrefix, rawForError, "unable to parse IP");
    }
    if (raw.indexOf(':') >= 0) {
      return parseIpv6WithOptionalZone(raw, errorPrefix, rawForError);
    }
    if (raw.indexOf('.') >= 0) {
      long ipv4 = parseIpv4(raw, errorPrefix, rawForError);
      return new ParsedIp(new CelIp((byte) 4, 0L, ipv4), null);
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

    int[] hextets = new int[8];
    int count = 0;
    int ellipsis = -1;
    int i = 0;

    if (s.charAt(0) == ':') {
      if (s.charAt(1) != ':') {
        throw parseError(errorPrefix, rawForError, "each colon-separated field must have a digit");
      }
      ellipsis = 0;
      i = 2;
    }

    while (i < len) {
      int segStart = i;
      int val = 0;
      int hexDigits = 0;
      boolean hitDot = false;

      while (i < len) {
        char c = s.charAt(i);
        int digit = hexValue(c);
        if (digit >= 0) {
          if (hexDigits == 4) {
            throw parseError(errorPrefix, rawForError, "IPv6 field has too many digits");
          }
          val = (val << 4) | digit;
          hexDigits++;
          i++;
        } else if (c == ':') {
          break;
        } else if (c == '.') {
          hitDot = true;
          break;
        } else {
          throw parseError(errorPrefix, rawForError, "unexpected character");
        }
      }

      if (hitDot) {
        if (segStart == 0) {
          throw parseError(errorPrefix, rawForError, "unexpected IPv4 prefix in IPv6 address");
        }
        if (count > 6 || (ellipsis >= 0 && count >= 6)) {
          throw parseError(errorPrefix, rawForError, "address too long");
        }
        long v4 = parseIpv4(s.substring(segStart), errorPrefix, rawForError);
        hextets[count++] = (int) ((v4 >>> 16) & 0xFFFFL);
        hextets[count++] = (int) (v4 & 0xFFFFL);
        i = len;
        break;
      }

      if (hexDigits == 0) {
        // Encountered ':' immediately after consuming the previous ':' -> "::".
        if (ellipsis >= 0) {
          throw parseError(errorPrefix, rawForError, "multiple :: in IPv6 address");
        }
        ellipsis = count;
        i++;
        continue;
      }

      if (count >= 8) {
        throw parseError(errorPrefix, rawForError, "address too long");
      }
      hextets[count++] = val;

      if (i == len - 1) {
        throw parseError(errorPrefix, rawForError, "trailing colon");
      }
      // Either i == len (exiting the outer loop) or s.charAt(i) == ':' (advancing past ':').
      i++;
    }

    int[] full;
    if (ellipsis < 0) {
      if (count != 8) {
        throw parseError(errorPrefix, rawForError, "address too short");
      }
      full = hextets;
    } else {
      if (count >= 8) {
        throw parseError(errorPrefix, rawForError, "address too long for :: compression");
      }
      int zeros = 8 - count;
      full = new int[8];
      for (int idx = 0; idx < ellipsis; idx++) {
        full[idx] = hextets[idx];
      }
      for (int idx = ellipsis; idx < count; idx++) {
        full[idx + zeros] = hextets[idx];
      }
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
    return new CelIp((byte) 6, hiVal, loVal);
  }

  private static int hexValue(char c) {
    if (c >= '0' && c <= '9') {
      return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
      return 10 + (c - 'a');
    }
    if (c >= 'A' && c <= 'F') {
      return 10 + (c - 'A');
    }
    return -1;
  }

  private static IllegalArgumentException parseError(
      String errorPrefix, String raw, String detail) {
    return new IllegalArgumentException(
        errorPrefix + " '" + raw + "' parse error during conversion from string: " + detail);
  }

  boolean is4In6() {
    return family == 6 && hi == 0L && (lo >>> 32) == 0xFFFFL;
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
    if (family == 4) {
      return lo == 0L;
    }
    return hi == 0L && lo == 0L;
  }

  /** Returns true if the IP address is a loopback address ({@code 127.0.0.0/8} or {@code ::1}). */
  public boolean isLoopback() {
    if (family == 4) {
      return ((lo >>> 24) & 0xFFL) == 127L;
    }
    return hi == 0L && lo == 1L;
  }

  /** Returns true if the IP address is a link-local unicast address. */
  public boolean isLinkLocalUnicast() {
    if (family == 4) {
      return ((lo >>> 24) & 0xFFL) == 169L && ((lo >>> 16) & 0xFFL) == 254L;
    }
    return ((hi >>> 56) & 0xFFL) == 0xFEL && ((hi >>> 48) & 0xC0L) == 0x80L;
  }

  /** Returns true if the IP address is a link-local multicast address. */
  public boolean isLinkLocalMulticast() {
    if (family == 4) {
      return ((lo >>> 24) & 0xFFL) == 224L
          && ((lo >>> 16) & 0xFFL) == 0L
          && ((lo >>> 8) & 0xFFL) == 0L;
    }
    return ((hi >>> 56) & 0xFFL) == 0xFFL && ((hi >>> 48) & 0x0FL) == 0x02L;
  }

  private boolean isMulticast() {
    if (family == 4) {
      return ((lo >>> 24) & 0xF0L) == 0xE0L;
    }
    return ((hi >>> 56) & 0xFFL) == 0xFFL;
  }

  /** Returns true if the IP address is a global unicast address per Go {@code netip} semantics. */
  public boolean isGlobalUnicast() {
    if (family == 4 && lo == 0xFFFFFFFFL) {
      return false;
    }
    return !isUnspecified() && !isLoopback() && !isMulticast() && !isLinkLocalUnicast();
  }

  CelIp mask(int prefixLength) {
    if (family == 4) {
      long v4Mask = prefixLength == 0 ? 0L : (0xFFFFFFFFL << (32 - prefixLength)) & 0xFFFFFFFFL;
      return new CelIp((byte) 4, 0L, lo & v4Mask);
    }
    long hiMask = ipv6HiMask(prefixLength);
    long loMask = ipv6LoMask(prefixLength);
    return new CelIp((byte) 6, hi & hiMask, lo & loMask);
  }

  boolean matchesPrefix(CelIp other, int prefixLength) {
    if (this.family != other.family) {
      return false;
    }
    if (family == 4) {
      long v4Mask = prefixLength == 0 ? 0L : (0xFFFFFFFFL << (32 - prefixLength)) & 0xFFFFFFFFL;
      return ((this.lo ^ other.lo) & v4Mask) == 0L;
    }
    long hiMask = ipv6HiMask(prefixLength);
    long loMask = ipv6LoMask(prefixLength);
    return ((this.hi ^ other.hi) & hiMask) == 0L && ((this.lo ^ other.lo) & loMask) == 0L;
  }

  private static long ipv6HiMask(int prefixLength) {
    if (prefixLength == 0) {
      return 0L;
    }
    if (prefixLength < 64) {
      return -1L << (64 - prefixLength);
    }
    return -1L;
  }

  private static long ipv6LoMask(int prefixLength) {
    if (prefixLength <= 64) {
      return 0L;
    }
    if (prefixLength < 128) {
      return -1L << (128 - prefixLength);
    }
    return -1L;
  }

  /** Returns the canonical string form of this IP address (RFC 5952 for IPv6). */
  public String toCanonicalString() {
    if (family == 4) {
      return ((lo >>> 24) & 0xFFL)
          + "."
          + ((lo >>> 16) & 0xFFL)
          + "."
          + ((lo >>> 8) & 0xFFL)
          + "."
          + (lo & 0xFFL);
    }
    int[] h = {
      (int) ((hi >>> 48) & 0xFFFFL),
      (int) ((hi >>> 32) & 0xFFFFL),
      (int) ((hi >>> 16) & 0xFFFFL),
      (int) (hi & 0xFFFFL),
      (int) ((lo >>> 48) & 0xFFFFL),
      (int) ((lo >>> 32) & 0xFFFFL),
      (int) ((lo >>> 16) & 0xFFFFL),
      (int) (lo & 0xFFFFL),
    };
    int bestStart = -1;
    int bestLen = 0;
    int curStart = -1;
    int curLen = 0;
    for (int i = 0; i < 8; i++) {
      if (h[i] == 0) {
        if (curStart < 0) {
          curStart = i;
          curLen = 1;
        } else {
          curLen++;
        }
        if (curLen > bestLen && curLen >= 2) {
          bestStart = curStart;
          bestLen = curLen;
        }
      } else {
        curStart = -1;
        curLen = 0;
      }
    }

    StringBuilder sb = new StringBuilder(39);
    for (int i = 0; i < 8; i++) {
      if (i == bestStart) {
        sb.append("::");
        i += bestLen - 1;
        continue;
      }
      if (i > 0 && !(bestStart >= 0 && i == bestStart + bestLen)) {
        sb.append(':');
      }
      sb.append(Integer.toHexString(h[i]));
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

  static final class ParsedIp {
    final CelIp ip;
    final @Nullable String zone;

    ParsedIp(CelIp ip, @Nullable String zone) {
      this.ip = ip;
      this.zone = zone;
    }
  }
}
