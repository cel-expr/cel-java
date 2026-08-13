// Copyright 2022 Google LLC
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

package dev.cel.runtime;

import com.google.auto.value.AutoOneOf;
import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.UnsignedLong;
import com.google.errorprone.annotations.Immutable;
import com.google.re2j.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * CelAttribute represents the select path from the root (.) to a single leaf value that may be
 * derived from the activation (e.g. .com.google.Attribute['index'])
 *
 * <p>This includes fully qualified identifiers and the selection path to a member field or element.
 *
 * <p>CelAttributes are represented as a list of qualifiers (select/index operations) from the root,
 * e.g. identifier[2].field is represented as string:identifier, int:2, string:field.
 */
@Immutable
@AutoValue
public abstract class CelAttribute {
  /**
   * Empty attribute. Represents a value that doesn't have a corresponding attribute (e.g. the
   * result of a function).
   */
  public static final CelAttribute EMPTY = new AutoValue_CelAttribute(ImmutableList.of());

  /** Representation of a single select qualifier or index. */
  @Immutable
  @AutoOneOf(Qualifier.Kind.class)
  public abstract static class Qualifier {
    private static final Pattern IDENT_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z_0-9]*");

    /** Legal attribute qualifier kinds. */
    public enum Kind {
      AS_STRING,
      AS_INT,
      AS_UINT,
      AS_BOOL,
      WILD_CARD
    };

    public static Qualifier ofString(String value) {
      return AutoOneOf_CelAttribute_Qualifier.asString(value);
    }

    public static Qualifier ofInt(long value) {
      return AutoOneOf_CelAttribute_Qualifier.asInt(value);
    }

    public static Qualifier ofUint(UnsignedLong value) {
      return AutoOneOf_CelAttribute_Qualifier.asUint(value);
    }

    /** Overload for integer literals. Value must be non-negative. */
    public static Qualifier ofUint(long value) {
      Preconditions.checkArgument(value >= 0L, "uint qualifier must be non-negative.");
      return ofUint(UnsignedLong.valueOf(value));
    }

    public static Qualifier ofBool(boolean value) {
      return AutoOneOf_CelAttribute_Qualifier.asBool(value);
    }

    public static Qualifier ofWildCard() {
      return AutoOneOf_CelAttribute_Qualifier.wildCard();
    }

    public abstract Kind kind();

    public abstract String asString();

    public abstract Long asInt();

    public abstract UnsignedLong asUint();

    public abstract Boolean asBool();

    public abstract void wildCard();

    /**
     * Creates a Qualifier from a generic object.
     *
     * @throws IllegalArgumentException if the value can't be interpreted as a field selection or an
     *     index.
     */
    public static Qualifier fromGeneric(Object value) {
      Qualifier qualifier = fromGenericOrNull(value);
      if (qualifier != null) {
        return qualifier;
      }
      throw new IllegalArgumentException("Unsupported attribute qualifier kind");
    }

    /**
     * Creates a Qualifier from a generic object, or null if the value cannot be interpreted as an
     * attribute qualifier.
     */
    @SuppressWarnings("IfChainToSwitch")
    public static @Nullable Qualifier fromGenericOrNull(Object value) {
      if (value instanceof UnsignedLong) {
        return ofUint((UnsignedLong) value);
      } else if (value instanceof Long) {
        return ofInt((Long) value);
      } else if (value instanceof Integer) {
        return ofInt(((Integer) value).longValue());
      } else if (value instanceof Boolean) {
        return ofBool((boolean) value);
      } else if (value instanceof String) {
        return ofString((String) value);
      }
      return null;
    }

    public String toIndexFormat() {
      String key = "";
      switch (kind()) {
        case AS_STRING:
          key = "'" + asString() + "'";
          break;
        case AS_INT:
          key = asInt().toString();
          break;
        case AS_UINT:
          key = asUint().toString() + "u";
          break;
        case AS_BOOL:
          key = asBool() ? "true" : "false";
          break;
        default:
          throw new IllegalStateException(
              String.format("Unsupported CEL attribute qualifier for index: %s", kind()));
      }
      return String.format("[%s]", key);
    }

    /**
     * Simple test that an identifier segment is a legal CEL identifier. This does not check for
     * reserved names.
     */
    public static boolean isLegalIdentifier(String identifier) {
      return IDENT_PATTERN.matches(identifier);
    }

    public static boolean isLegalIdentifier(Qualifier identifier) {
      return identifier.kind() == Kind.AS_STRING && isLegalIdentifier(identifier.asString());
    }
  }

  /** Creates a CelAttribute. */
  public static CelAttribute create(ImmutableList<Qualifier> qualifiers) {
    Preconditions.checkArgument(!qualifiers.isEmpty(), "qualifiers must be non-empty");
    Preconditions.checkArgument(
        Qualifier.isLegalIdentifier(qualifiers.get(0)),
        "'%s' is not a legal CEL identifier",
        qualifiers.get(0));

    qualifiers.forEach(
        q ->
            Preconditions.checkArgument(
                q.kind() != Qualifier.Kind.WILD_CARD,
                "Wildcards are not permitted in CelAttributes."));

    return new AutoValue_CelAttribute(qualifiers);
  }

  /** Creates a CelAttribute from a single root identifier. */
  public static CelAttribute create(String rootIdentifier) {
    Preconditions.checkArgument(
        Qualifier.isLegalIdentifier(rootIdentifier),
        "'%s' is not a legal CEL identifier",
        rootIdentifier);

    return new AutoValue_CelAttribute(ImmutableList.of(Qualifier.ofString(rootIdentifier)));
  }

  /**
   * Attempts to parse a dot qualified identifier into a CEL attribute.
   *
   * @throws IllegalArgumentException if qualifiedIdentifier isn't a legal qualified identifier.
   *     Note: this is intended for use with reference names in a checked CEL expression -- it does
   *     not check for some edge cases (e.g. reserved words).
   */
  public static CelAttribute fromQualifiedIdentifier(String qualifiedIdentifier) {
    ImmutableList.Builder<Qualifier> qualifiers = ImmutableList.builder();
    int start = 0;
    int next;
    while ((next = qualifiedIdentifier.indexOf('.', start)) != -1) {
      qualifiers.add(Qualifier.ofString(qualifiedIdentifier.substring(start, next)));
      start = next + 1;
    }
    qualifiers.add(Qualifier.ofString(qualifiedIdentifier.substring(start)));
    return new AutoValue_CelAttribute(qualifiers.build());
  }

  /** The list of qualifiers representing the select path for this attribute. */
  public abstract ImmutableList<Qualifier> qualifiers();

  /**
   * Creates a new attribute that is more qualified (has an additional select or index operation)
   * than the receiver.
   */
  public CelAttribute qualify(Qualifier qualifier) {
    Preconditions.checkArgument(
        qualifier.kind() != Qualifier.Kind.WILD_CARD,
        "Wildcards are not permitted for attributes.");
    // To simplify client code, qualifying the empty singleton is still empty.
    if (qualifiers().isEmpty()) {
      return EMPTY;
    }
    return new AutoValue_CelAttribute(
        ImmutableList.<Qualifier>builderWithExpectedSize(qualifiers().size() + 1).addAll(qualifiers()).add(qualifier).build());
  }

  @Override
  public final String toString() {
    if (this.equals(EMPTY)) {
      return "<empty>";
    }
    Preconditions.checkState(
        !qualifiers().isEmpty() && qualifiers().get(0).kind() == Qualifier.Kind.AS_STRING,
        "CelAttribute must have a root qualifier that is a legal identifier");

    StringBuilder cname = new StringBuilder(qualifiers().get(0).asString());
    for (Qualifier qualifier : qualifiers().subList(1, qualifiers().size())) {
      if (qualifier.kind() == Qualifier.Kind.AS_STRING && Qualifier.isLegalIdentifier(qualifier)) {
        cname.append(".").append(qualifier.asString());
      } else {
        cname.append(qualifier.toIndexFormat());
      }
    }
    return cname.toString();
  }
}
