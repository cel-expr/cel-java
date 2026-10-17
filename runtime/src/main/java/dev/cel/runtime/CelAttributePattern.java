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

import static java.lang.Math.min;

import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.UnsignedLong;
import com.google.errorprone.annotations.Immutable;

/**
 * A Pattern for matching against {@link CelAttribute}s.
 *
 * <p>CelAttributePatterns are structured the same as attributes, but permit some qualifiers be
 * replaced with wildcards.
 */
@Immutable
@AutoValue
public abstract class CelAttributePattern {
  /** Constructs a CelAttributePattern from a list of qualifiers. */
  public static CelAttributePattern create(ImmutableList<CelAttribute.Qualifier> qualifiers) {
    Preconditions.checkArgument(!qualifiers.isEmpty(), "qualifiers must be non-empty");
    Preconditions.checkArgument(
        CelAttribute.Qualifier.isLegalIdentifier(qualifiers.get(0)),
        "'%s' is not a legal CEL identifier",
        qualifiers.get(0));

    return new AutoValue_CelAttributePattern(qualifiers);
  }

  /** Constructs a pattern from a single root identifier (single string qualifier). */
  public static CelAttributePattern create(String rootIdentifier) {
    Preconditions.checkArgument(
        CelAttribute.Qualifier.isLegalIdentifier(rootIdentifier),
        "'%s' is not a legal CEL identifier",
        rootIdentifier);

    return new AutoValue_CelAttributePattern(
        ImmutableList.of(CelAttribute.Qualifier.ofString(rootIdentifier)));
  }

  /**
   * Attempts to parse a dot qualified identifier into a CEL attribute.
   *
   * @throws IllegalArgumentException if qualifiedIdentifier isn't a legal qualified identifier.
   *     Note: this is intended for use with reference names in a checked CEL expression -- it does
   *     not check for some edge cases (e.g. reserved words).
   */
  public static CelAttributePattern fromQualifiedIdentifier(String qualifiedIdentifier) {
    ImmutableList.Builder<CelAttribute.Qualifier> qualifiers = ImmutableList.builder();
    int start = 0;
    int next;
    while ((next = qualifiedIdentifier.indexOf('.', start)) != -1) {
      qualifiers.add(CelAttribute.Qualifier.ofString(qualifiedIdentifier.substring(start, next)));
      start = next + 1;
    }
    qualifiers.add(CelAttribute.Qualifier.ofString(qualifiedIdentifier.substring(start)));
    return new AutoValue_CelAttributePattern(qualifiers.build());
  }

  /** The list of qualifiers representing the select paths this pattern matches. */
  public abstract ImmutableList<CelAttribute.Qualifier> qualifiers();

  /** Create a new attribute pattern that specifies a subfield of this pattern. */
  public CelAttributePattern qualify(CelAttribute.Qualifier qualifier) {
    return new AutoValue_CelAttributePattern(
        ImmutableList.<CelAttribute.Qualifier>builderWithExpectedSize(qualifiers().size() + 1)
            .addAll(qualifiers())
            .add(qualifier)
            .build());
  }

  @Override
  public final String toString() {
    Preconditions.checkState(
        !qualifiers().isEmpty()
            && qualifiers().get(0).kind() == CelAttribute.Qualifier.Kind.AS_STRING,
        "CelAttribute must have a root qualifier that is a legal identifier");

    StringBuilder cname = new StringBuilder(qualifiers().get(0).asString());
    for (CelAttribute.Qualifier qualifier : qualifiers().subList(1, qualifiers().size())) {
      switch (qualifier.kind()) {
        case WILD_CARD:
          cname.append(".*");
          break;
        case AS_STRING:
          if (CelAttribute.Qualifier.isLegalIdentifier(qualifier)) {
            cname.append(".").append(qualifier.asString());
          } else {
            cname.append(qualifier.toIndexFormat());
          }
          break;
        default:
          cname.append(qualifier.toIndexFormat());
          break;
      }
    }
    return cname.toString();
  }

  /**
   * Return whether this pattern matches the given attribute.
   *
   * <p>A pattern matches an attribute if the pattern contains the attribute (e.g. fully matches the
   * attribute or one of its parents).
   */
  public boolean isMatch(CelAttribute attribute) {
    if (attribute.equals(CelAttribute.EMPTY)) {
      return false;
    }
    int qualifierCount = min(qualifiers().size(), attribute.qualifiers().size());
    int i = 0;
    for (; i < qualifierCount; i++) {
      if (!matches(qualifiers().get(i), attribute.qualifiers().get(i))) {
        return false;
      }
    }
    // If the end of the loop is reached, either the pattern was exhausted (full match) or
    // the attribute was exhausted (partial match / a sub field was matched).
    return i >= qualifiers().size();
  }

  /**
   * Return whether this pattern matches the given attribute or any of its descendants.
   *
   * <p>A partial match indicates that the attribute may contain some missing data, so the complete
   * object is may be in an undefined state. For example:
   *
   * <ul>
   *   <li>pattern (this) object.field.list_field
   *   <li>attribute (arg) object.field
   * </ul>
   *
   * is not a full match, but is a partial match.
   */
  public boolean isPartialMatch(CelAttribute attribute) {
    if (attribute.equals(CelAttribute.EMPTY)) {
      return false;
    }
    int qualifierCount = min(qualifiers().size(), attribute.qualifiers().size());
    for (int i = 0; i < qualifierCount; i++) {
      if (!matches(qualifiers().get(i), attribute.qualifiers().get(i))) {
        return false;
      }
    }
    // If the end of the loop is reached, either the pattern was exhausted (full match) or
    // the attribute was exhausted (partial match / a sub field was matched).
    return true;
  }

  // Numeric qualifiers match by value across int and uint, as map keys and list indices do.
  private static boolean matches(CelAttribute.Qualifier pattern, CelAttribute.Qualifier qualifier) {
    if (pattern.kind() == CelAttribute.Qualifier.Kind.WILD_CARD || pattern.equals(qualifier)) {
      return true;
    }
    if (pattern.kind() == CelAttribute.Qualifier.Kind.AS_INT
        && qualifier.kind() == CelAttribute.Qualifier.Kind.AS_UINT) {
      return intEqualsUint(pattern.asInt(), qualifier.asUint());
    }
    return pattern.kind() == CelAttribute.Qualifier.Kind.AS_UINT
        && qualifier.kind() == CelAttribute.Qualifier.Kind.AS_INT
        && intEqualsUint(qualifier.asInt(), pattern.asUint());
  }

  private static boolean intEqualsUint(long intValue, UnsignedLong uintValue) {
    return intValue >= 0 && UnsignedLong.valueOf(intValue).equals(uintValue);
  }

  /**
   * Return an appropriate attribute for a pattern match.
   *
   * <p>For a partial match, return the attribute.
   *
   * <p>For a full match, return the (possibly parent attribute)
   */
  public CelAttribute simplify(CelAttribute candidate) {
    int patternLen = this.qualifiers().size();
    if (patternLen < candidate.qualifiers().size()) {
      return CelAttribute.create(candidate.qualifiers().subList(0, patternLen));
    }
    return candidate;
  }
}
