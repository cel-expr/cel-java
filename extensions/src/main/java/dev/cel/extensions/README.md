# Extensions

CEL extensions are a related set of constants, functions, macros, or other
features which may not be covered by the core CEL spec.

To use, supply the desired extensions into `CelCompiler` and `CelRuntime`
through `addLibrary` methods in their builder:

    CelCompilerFactory.standardCelCompilerBuilder().addLibraries(CelExtensions.strings())
    CelRuntimeFactory.standardCelRuntimeBuilder().addLibraries(CelExtensions.strings())

## Cel

The `cel` namespace is reserved for linguistic constructs which augment the
expressiveness of the language.

### Cel.Bind

Binds a simple identifier to an initialization expression which may be used in a
subsequent result expression. Bindings may also be nested within each other.

    cel.bind(<varName>, <initExpr>, <resultExpr>)

Examples:

    cel.bind(a, 'hello',
        cel.bind(b, 'world', a + b + b + a)) // "helloworldworldhello"

    // Avoid a list allocation within the exists comprehension.
    cel.bind(valid_values, [a, b, c],
        [d, e, f].exists(elem, elem in valid_values))

    cel.bind(sum, x + y + z, sum < 10 || sum > 20)

Local bindings are not guaranteed to be evaluated before use.

## Math

Math configures namespaced math helper macros and functions.

Note, all macros use the 'math' namespace; however, at the time of macro
expansion the namespace looks just like any other identifier. If you are
currently using a variable named 'math', the macro will likely work just as
intended; however, there is some chance for collision.

### Math.Greatest

Returns the greatest valued number present in the arguments to the macro.

Greatest is a variable argument count macro which must take at least one
argument. Simple numeric and list literals are supported as valid argument
types; however, other literals will be flagged as errors during macro
expansion. If the argument expression does not resolve to a numeric or
list(numeric) type during type-checking, or during runtime then an error
will be produced. If a list argument is empty, this too will produce an
error.

    math.greatest(<arg>, ...) -> <double|int|uint>

Examples:

    math.greatest(1)      // 1
    math.greatest(1u, 2u) // 2u
    math.greatest(-42.0, -21.5, -100.0)   // -21.5
    math.greatest([-42.0, -21.5, -100.0]) // -21.5
    math.greatest(numbers) // numbers must be list(numeric)

    math.greatest()         // parse error
    math.greatest('string') // parse error
    math.greatest(a, b)     // check-time error if a or b is non-numeric
    math.greatest(dyn('string')) // runtime error

### Math.Least

Returns the least valued number present in the arguments to the macro.

Least is a variable argument count macro which must take at least one
argument. Simple numeric and list literals are supported as valid argument
types; however, other literals will be flagged as errors during macro
expansion. If the argument expression does not resolve to a numeric or
list(numeric) type during type-checking, or during runtime then an error
will be produced. If a list argument is empty, this too will produce an error.

    math.least(<arg>, ...) -> <double|int|uint>

Examples:

    math.least(1)      // 1
    math.least(1u, 2u) // 1u
    math.least(-42.0, -21.5, -100.0)   // -100.0
    math.least([-42.0, -21.5, -100.0]) // -100.0
    math.least(numbers) // numbers must be list(numeric)

    math.least()         // parse error
    math.least('string') // parse error
    math.least(a, b)     // check-time error if a or b is non-numeric
    math.least(dyn('string')) // runtime error

### Math.BitOr

Introduced at version: 1

Performs a bitwise-OR operation over two int or uint values.

    math.bitOr(<int>, <int>) -> <int>
    math.bitOr(<uint>, <uint>) -> <uint>

Examples:

    math.bitOr(1u, 2u)    // returns 3u
    math.bitOr(-2, -4)    // returns -2

### Math.BitAnd

Introduced at version: 1

Performs a bitwise-AND operation over two int or uint values.

    math.bitAnd(<int>, <int>) -> <int>
    math.bitAnd(<uint>, <uint>) -> <uint>

Examples:

    math.bitAnd(3u, 2u)   // return 2u
    math.bitAnd(3, 5)     // returns 3
    math.bitAnd(-3, -5)   // returns -7

### Math.BitXor

Introduced at version: 1

    math.bitXor(<int>, <int>) -> <int>
    math.bitXor(<uint>, <uint>) -> <uint>

Performs a bitwise-XOR operation over two int or uint values.

Examples:

    math.bitXor(3u, 5u) // returns 6u
    math.bitXor(1, 3)   // returns 2

### Math.BitNot

Introduced at version: 1

Function which accepts a single int or uint and performs a bitwise-NOT
ones-complement of the given binary value.

    math.bitNot(<int>) -> <int>
    math.bitNot(<uint>) -> <uint>

Examples

    math.bitNot(1)  // returns -1
    math.bitNot(-1) // return 0
    math.bitNot(0u) // returns 18446744073709551615u

### Math.BitShiftLeft

Introduced at version: 1

Perform a left shift of bits on the first parameter, by the amount of bits
specified in the second parameter. The first parameter is either a uint or
an int. The second parameter must be an int.

When the second parameter is 64 or greater, 0 will be always be returned
since the number of bits shifted is greater than or equal to the total bit
length of the number being shifted. Negative valued bit shifts will result
in a runtime error.

    math.bitShiftLeft(<int>, <int>) -> <int>
    math.bitShiftLeft(<uint>, <int>) -> <uint>

Examples

    math.bitShiftLeft(1, 2)    // returns 4
    math.bitShiftLeft(-1, 2)   // returns -4
    math.bitShiftLeft(1u, 2)   // return 4u
    math.bitShiftLeft(1u, 200) // returns 0u

### Math.BitShiftRight

Introduced at version: 1

Perform a right shift of bits on the first parameter, by the amount of bits
specified in the second parameter. The first parameter is either a uint or
an int. The second parameter must be an int.

When the second parameter is 64 or greater, 0 will always be returned since
the number of bits shifted is greater than or equal to the total bit length
of the number being shifted. Negative valued bit shifts will result in a
runtime error.

The sign bit extension will not be preserved for this operation: vacant bits
on the left are filled with 0.

    math.bitShiftRight(<int>, <int>) -> <int>
    math.bitShiftRight(<uint>, <int>) -> <uint>

Examples

    math.bitShiftRight(1024, 2)    // returns 256
    math.bitShiftRight(1024u, 2)   // returns 256u
    math.bitShiftRight(1024u, 64)  // returns 0u

### Math.Ceil

Introduced at version: 1

Compute the ceiling of a double value.

    math.ceil(<double>) -> <double>

Examples:

    math.ceil(1.2)   // returns 2.0
    math.ceil(-1.2)  // returns -1.0

### Math.Floor

Introduced at version: 1

Compute the floor of a double value.

    math.floor(<double>) -> <double>

Examples:

    math.floor(1.2)   // returns 1.0
    math.floor(-1.2)  // returns -2.0

### Math.Round

Introduced at version: 1

Rounds the double value to the nearest whole number with ties rounding away
from zero, e.g. 1.5 -> 2.0, -1.5 -> -2.0.

    math.round(<double>) -> <double>

Examples:

    math.round(1.2)  // returns 1.0
    math.round(1.5)  // returns 2.0
    math.round(-1.5) // returns -2.0

### Math.Trunc

Introduced at version: 1

Truncates the fractional portion of the double value.

    math.trunc(<double>) -> <double>

Examples:

    math.trunc(-1.3)  // returns -1.0
    math.trunc(1.3)   // returns 1.0

### Math.Abs

Introduced at version: 1

Returns the absolute value of the numeric type provided as input. If the
value is NaN, the output is NaN. If the input is int64 min, the function
will result in an overflow error.

    math.abs(<double>) -> <double>
    math.abs(<int>) -> <int>
    math.abs(<uint>) -> <uint>

Examples:

    math.abs(-1)  // returns 1
    math.abs(1)   // returns 1
    math.abs(-9223372036854775808) // overlflow error

### Math.Sign

Introduced at version: 1

Returns the sign of the numeric type, either -1, 0, 1 as an int, double, or
uint depending on the overload. For floating point values, if NaN is
provided as input, the output is also NaN. The implementation does not
differentiate between positive and negative zero.

    math.sign(<double>) -> <double>
    math.sign(<int>) -> <int>
    math.sign(<uint>) -> <uint>

Examples:

    math.sign(-42) // returns -1
    math.sign(0)   // returns 0
    math.sign(42)  // returns 1

### Math.IsInf

Introduced at version: 1

Returns true if the input double value is -Inf or +Inf.

    math.isInf(<double>) -> <bool>

Examples:

    math.isInf(1.0/0.0)  // returns true
    math.isInf(1.2)      // returns false

### Math.IsNaN

Introduced at version: 1

Returns true if the input double value is NaN, false otherwise.

    math.isNaN(<double>) -> <bool>

Examples:

    math.isNaN(0.0/0.0)  // returns true
    math.isNaN(1.2)      // returns false

### Math.IsFinite

Introduced at version: 1

Returns true if the value is a finite number. Equivalent in behavior to:
!math.isNaN(double) && !math.isInf(double)

    math.isFinite(<double>) -> <bool>

Examples:

    math.isFinite(0.0/0.0)  // returns false
    math.isFinite(1.2)      // returns true

### Math.sqrt

Introduced at version: 2

Returns the square root of the numeric type provided as input. If the value is
NaN, the output is NaN. If the input is negative, the output is NaN.

    math.sqrt(<double>) -> <double>
    math.sqrt(<int>) -> <double>
    math.sqrt(<uint>) -> <double>

Examples:

    math.sqrt(81.0)  // returns 9.0
    math.sqrt(4)     // returns 2.0
    math.sqrt(-4)    // returns NaN

## Protos

Extended macros and functions for proto manipulation.

Protos configures extended macros and functions for proto manipulation.

Note, all macros use the 'proto' namespace; however, at the time of macro
expansion the namespace looks just like any other identifier. If you are
currently using a variable named 'proto', the macro will likely work just as
you intend; however, there is some chance for collision.

### Proto.GetExt

Macro which generates a select expression that retrieves an extension field
from the input proto2 syntax message. If the field is not set, the default
value for the extension field is returned according to safe-traversal semantics.

    proto.getExt(<msg>, <fully.qualified.extension.name>) -> <field-type>

Example:

    proto.getExt(msg, google.expr.proto2.test.int32_ext) // returns int value

### Proto.HasExt

Macro which generates a test-only select expression that determines whether
an extension field is set on a proto2 syntax message.

    proto.hasExt(<msg>, <fully.qualified.extension.name>) -> <bool>

Example:

    proto.hasExt(msg, google.expr.proto2.test.int32_ext) // returns true || false

## Strings

Extended functions for string manipulation. As a general note, all indices are
zero-based.

### CharAt

Returns the character at the given position. If the position is negative, or greater than
the length of the string, the function will produce an error.

    <string>.charAt(<int>) -> <string>

Examples:

    'hello'.charAt(4)  // return 'o'
    'hello'.charAt(5)  // return ''
    'hello'.charAt(-1) // error

### Format

Returns a new string with substitutions being performed, printf-style.

The formatting behavior adheres to the
[CEL string extensions spec](https://github.com/google/cel-spec/blob/master/doc/extensions/strings.md).
Formatting is locale-independent and rounding is half-even (banker's rounding),
so that results agree with the other CEL runtimes.

The valid formatting clauses are:

-   `%s` - substitutes a string. This can also be used on bools, lists, maps,
    bytes, Duration and Timestamp, in addition to all numerical types (int,
    uint, and double). Invalid UTF-8 in bytes is replaced with \uFFFD, and map
    entries are sorted by the string representation of their keys.
-   `%d` - substitutes an integer.
-   `%f` - substitutes a double with fixed-point precision. The default
    precision is 6, but this can be adjusted.
-   `%e` - substitutes a double in scientific notation. The default precision is
    6, but this can be adjusted.
-   `%b` - substitutes an integer with its equivalent binary string. Can also be
    used on bools.
-   `%x` - substitutes an integer with its equivalent in hexadecimal, or if
    given a string or bytes, outputs each character's equivalent in
    hexadecimal.
-   `%X` - same as `%x`, but with A-F capitalized.
-   `%o` - substitutes an integer with its equivalent in octal.

A precision is written between the `%` and the verb (e.g. `%.3f`) and may not
exceed 100. `%%` emits a literal `%` and does not consume an argument.

    <string>.format(<list>) -> <string>

Examples:

    'this is a string: %s and an integer: %d'.format(['str', 42]) // returns 'this is a string: str and an integer: 42'
    '%f'.format([3.14])                           // returns '3.140000'
    '%.3f'.format([1.2345])                       // returns '1.234'
    '%e'.format([1234.0])                         // returns '1.234000e+03'
    '5 in binary: %b'.format([5])                 // returns '5 in binary: 101'
    '26 in hex: %x'.format([26])                  // returns '26 in hex: 1a'
    '26 in hex (uppercase): %X'.format([26])      // returns '26 in hex (uppercase): 1A'
    '30 in octal: %o'.format([30])                // returns '30 in octal: 36'
    'duration: %s'.format([duration('1h45m47s')]) // returns 'duration: 6347s'
    'a map: %s'.format([{'b': 1, 'a': 2}])        // returns 'a map: {a: 2, b: 1}'
    '100%%'.format([])                            // returns '100%'

Passing an incorrect type (a string to `%b`) is an error, as is attempting to
use more formatting clauses than there are arguments.

### IndexOf

Returns the integer index of the first occurrence of the search string. If the search string is
not found the function returns -1.

The function also accepts an optional offset from which to begin the substring search. If the
substring is the empty string, the index where the search starts is returned (zero or custom).

    <string>.indexOf(<string>) -> <int>
    <string>.indexOf(<string>, <int>) -> <int>

Examples:

    'hello mellow'.indexOf('')         // returns 0
    'hello mellow'.indexOf('ello')     // returns 1
    'hello mellow'.indexOf('jello')    // returns -1
    'hello mellow'.indexOf('', 2)      // returns 2
    'hello mellow'.indexOf('ello', 2)  // returns 7
    'hello mellow'.indexOf('ello', 20) // error

### Join

Returns a new string where the elements of string list are concatenated.

The function also accepts an optional separator which is placed between elements in the resulting string.

    <list<string>>.join() -> <string>
    <list<string>>.join(<string>) -> <string>

Examples:

    ['hello', 'mellow'].join() // returns 'hellomellow'
    ['hello', 'mellow'].join(' ') // returns 'hello mellow'
    [].join() // returns ''
    [].join('/') // returns ''

### LastIndexOf

Returns the integer index of the last occurrence of the search string. If the
search string is not found the function returns -1.

The function also accepts an optional offset which represents the last index
to be considered as the beginning of the substring match. If the substring is
the empty string, the index where the search starts is returned (string length
or custom).

    <string>.lastIndexOf(<string>) -> <int>
    <string>.lastIndexOf(<string>, <int>) -> <int>

Examples:

    'hello mellow'.lastIndexOf('')         // returns 12
    'hello mellow'.lastIndexOf('ello')     // returns 7
    'hello mellow'.lastIndexOf('jello')    // returns -1
    'hello mellow'.lastIndexOf('ello', 6)  // returns 1
    'hello mellow'.lastIndexOf('ello', -1) // error

### LowerAscii

Returns a new string where all ASCII characters are lower-cased.

This function does not perform Unicode case-mapping for characters outside the
ASCII range.

     <string>.lowerAscii() -> <string>

Examples:

     'TacoCat'.lowerAscii()      // returns 'tacocat'
     'TacoCÆt Xii'.lowerAscii()  // returns 'tacocÆt xii'

### Quote

Takes the given string and makes it safe to print (without any formatting due
to escape sequences).
If any invalid UTF-8 characters are encountered, they are replaced with \uFFFD.

    strings.quote(<string>)

Examples:

    strings.quote('single-quote with "double quote"') // returns '"single-quote with \"double quote\""'
    strings.quote("two escape sequences \a\n") // returns '"two escape sequences \\a\\n"'

### Replace

Returns a new string based on the target, which replaces the occurrences of a
search string with a replacement string if present. The function accepts an
optional limit on the number of substring replacements to be made.

When the replacement limit is 0, the result is the original string. When the
limit is a negative number, the function behaves the same as replace all.

    <string>.replace(<string>, <string>) -> <string>
    <string>.replace(<string>, <string>, <int>) -> <string>

Examples:

    'hello hello'.replace('he', 'we')     // returns 'wello wello'
    'hello hello'.replace('he', 'we', -1) // returns 'wello wello'
    'hello hello'.replace('he', 'we', 1)  // returns 'wello hello'
    'hello hello'.replace('he', 'we', 0)  // returns 'hello hello'

### Reverse

Returns a new string whose characters are the same as the target string, only
formatted in reverse order.
This function relies on converting strings to Unicode code point arrays in
order to reverse.

    <string>.reverse() -> <string>

Examples:

    'gums'.reverse() // returns 'smug'
    'John Smith'.reverse() // returns 'htimS nhoJ'

### Split

Returns a list of strings split from the input by the given separator. The
function accepts an optional argument specifying a limit on the number of
substrings produced by the split.

When the split limit is 0, the result is an empty list. When the limit is 1,
the result is the target string to split. When the limit is a negative
number, the function behaves the same as split all.

    <string>.split(<string>) -> <list<string>>
    <string>.split(<string>, <int>) -> <list<string>>

Examples:

    'hello hello hello'.split(' ')     // returns ['hello', 'hello', 'hello']
    'hello hello hello'.split(' ', 0)  // returns []
    'hello hello hello'.split(' ', 1)  // returns ['hello hello hello']
    'hello hello hello'.split(' ', 2)  // returns ['hello', 'hello hello']
    'hello hello hello'.split(' ', -1) // returns ['hello', 'hello', 'hello']

### Substring

Returns the substring given a numeric range corresponding to character
positions. Optionally may omit the trailing range for a substring from a given
character position until the end of a string.

Character offsets are 0-based with an inclusive start range and exclusive end
range. It is an error to specify an end range that is lower than the start
range, or for either the start or end index to be negative or exceed the string
length.

    <string>.substring(<int>) -> <string>
    <string>.substring(<int>, <int>) -> <string>

Examples:

    'tacocat'.substring(4)    // returns 'cat'
    'tacocat'.substring(0, 4) // returns 'taco'
    'tacocat'.substring(-1)   // error
    'tacocat'.substring(2, 1) // error

### Trim

Returns a new string which removes the leading and trailing whitespace in the
target string. The trim function uses the Unicode definition of whitespace
which does not include the zero-width spaces. See:
https://en.wikipedia.org/wiki/Whitespace_character#Unicode

    <string>.trim() -> <string>

Examples:

    '  \ttrim\n    '.trim() // returns 'trim'

### UpperAscii

Returns a new string where all ASCII characters are upper-cased.

This function does not perform Unicode case-mapping for characters outside the
ASCII range.

    <string>.upperAscii() -> <string>

Examples:

    'TacoCat'.upperAscii()      // returns 'TACOCAT'
    'TacoCÆt Xii'.upperAscii()  // returns 'TACOCÆT XII'

## Encoders

Encoding utilities for marshalling data into standardized representations.

### Base64.Decode

Decodes base64-encoded string to bytes.

This function will return an error if the string input is not
base64-encoded.

    base64.decode(<string>) -> <bytes>

Examples:

    base64.decode('aGVsbG8=')  // return b'hello'
    base64.decode('aGVsbG8')   // return b'hello'. Note that the padding
                               // character can be omitted.
    base64.decode('z!')        // error

### Base64.Encode

Encodes bytes to a base64-encoded string. Note that the string is encoded in
ISO_8859_1.

    base64.encode(<bytes>)  -> <string>

Example:

    base64.encode(b'hello') // return 'aGVsbG8='

## Sets

Sets provides set relationship tests.

There is no set type within CEL, and while one may be introduced in the future,
there are cases where a `list` type is known to behave like a set. For such
cases, this library provides some basic functionality for determining set
containment, equivalence, and intersection.

### Sets.Contains

Returns whether the first list argument contains all elements in the second list
argument. The list may contain elements of any type and standard CEL equality is
used to determine whether a value exists in both lists. If the second list is
empty, the result will always return true.

```
sets.contains(list(T), list(T)) -> bool
```

Examples:

```
sets.contains([], []) // true
sets.contains([], [1]) // false
sets.contains([1, 2, 3, 4], [2, 3]) // true
sets.contains([1, 2.0, 3u], [1.0, 2u, 3]) // true
```

### Sets.Equivalent

Returns whether the first and second list are set equivalent. Lists are set
equivalent if for every item in the first list, there is an element in the
second which is equal. The lists may not be of the same size as they do not
guarantee the elements within them are unique, so size does not factor into the
computation.

```
sets.equivalent(list(T), list(T)) -> bool
```

Examples:

```
sets.equivalent([], []) // true
sets.equivalent([1], [1, 1]) // true
sets.equivalent([1], [1u, 1.0]) // true
sets.equivalent([1, 2, 3], [3u, 2.0, 1]) // true
```

### Sets.Intersects

Returns whether the first list has at least one element whose value is equal to
an element in the second list. If either list is empty, the result will be
false.

```
sets.intersects(list(T), list(T)) -> bool
```

Examples:

```
sets.intersects([1], []) // false
sets.intersects([1], [1, 2]) // true
sets.intersects([[1], [2, 3]], [[1, 2], [2, 3.0]]) // true
```

## Lists

Extended functions for list manipulation. As a general note, all indices are
zero-based.

### Slice

Returns a new sub-list using the indexes provided. The `from` index is
inclusive, the `to` index is exclusive.

    <list>.slice(<int>, <int>) -> <list>

Examples:

    [1,2,3,4].slice(1, 3) // return [2, 3]
    [1,2,3,4].slice(2, 4) // return [3, 4]

### Flatten

Introduced at version: 1

Flattens a list by one level, or to the specified level. Providing a negative level will error.

Examples:

```
// Single-level flatten:

[].flatten() // []
[1,[2,3],[4]].flatten() // [1, 2, 3, 4]
[1,[2,[3,4]]].flatten() // [1, 2, [3, 4]]
[1,2,[],[],[3,4]].flatten() // [1, 2, 3, 4]

// Recursive flatten
[1,[2,[3,[4]]]].flatten(2) // return [1, 2, 3, [4]]
[1,[2,[3,[4]]]].flatten(3) // return [1, 2, 3, 4]

// Error
[1,[2,[3,[4]]]].flatten(-1)
```

Note that due to the current limitations of type-checker, a compilation error
will occur if an already flat list is populated to the argument-less flatten
function.

For time being, you must explicitly provide 1 as the depth level, or wrap the
list in dyn if you anticipate having to deal with a flat list:

```
[1,2,3].flatten() // error

// But the following will work:
[1,2,3].flatten(1) // [1,2,3]
dyn([1,2,3]).flatten() // [1,2,3]
```

This will be addressed once we add the appropriate capabilities in the
type-checker to handle type-reductions, or union types.

### Range

Introduced at version: 2

Given integer size n returns a list of integers from 0 to n-1. If size <= 0
then return empty list.

```
lists.range(int) -> list(int)
```

Examples:

```
lists.range(5) -> [0, 1, 2, 3, 4]
lists.range(0) -> []
```

### Distinct

Introduced at version: 2

Returns the distinct elements of a list.

    <list(T)>.distinct() -> <list(T)>

Examples:

    [1, 2, 2, 3, 3, 3].distinct() // return [1, 2, 3]
    ["b", "b", "c", "a", "c"].distinct() // return ["b", "c", "a"]
    [1, "b", 2, "b"].distinct() // return [1, "b", 2]
    [1, 1.0, 2, 2u].distinct() // return [1, 2]

### Reverse

Introduced in version 2

Returns the elements of a list in reverse order.

    <list(T)>.reverse() -> <list(T)>

Examples:

    [5, 3, 1, 2].reverse() // return [2, 1, 3, 5]

### Sort

Introduced in version 2

Sorts a list with comparable elements. If the element type is not comparable
or the element types are not the same, the function will produce an error.

    <list(T)>.sort() -> <list(T)>
    // T in {int, uint, double, bool, duration, timestamp, string, bytes}

Examples:

    [3, 2, 1].sort() // return [1, 2, 3]
    ["b", "c", "a"].sort() // return ["a", "b", "c"]
    [1, "b"].sort() // error
    [[1, 2, 3]].sort() // error

### SortBy

Introduced in version 2

Sorts a list by a key value, i.e., the order is determined by the result of
an expression applied to each element of the list.

    <list(T)>.sortBy(<bindingName>, <keyExpr>) -> <list(T)>
    keyExpr returns a value in {int, uint, double, bool, duration, timestamp, string, bytes}

Examples:

    [
      Player { name: "foo", score: 0 },
      Player { name: "bar", score: -10 },
      Player { name: "baz", score: 1000 },
    ].sortBy(e, e.score).map(e, e.name)
    == ["bar", "foo", "baz"]

### Last

Introduced in the 'optional' extension version 2

Returns an optional with the last value from the list or `optional.None` if the
list is empty.

    <list(T)>.last() -> <Optional(T)>

Examples:

    [1, 2, 3].last().value() == 3
    [].last().orValue('test') == 'test'

This is syntactic sugar for list[list.size()-1].

### First

Introduced in the 'optional' extension version 2

Returns an optional with the first value from the list or `optional.None` if the
list is empty.

    <list(T)>.first() -> <Optional(T)>

Examples:

     [1, 2, 3].first().value() == 1
     [].first().orValue('test') == 'test'

## Regex

Regex introduces support for regular expressions in CEL.

This library provides functions for capturing groups, replacing strings using
regex patterns, Regex configures namespaced regex helper functions. Note, all
functions use the 'regex' namespace. If you are currently using a variable named
'regex', the macro will likely work just as intended; however, there is some
chance for collision.

### Replace

The `regex.replace` function replaces all non-overlapping substring of a regex
pattern in the target string with a replacement string. Optionally, you can
limit the number of replacements by providing a count argument. When the count
is a negative number, the function acts as replace all. Only numeric (\N)
capture group references are supported in the replacement string, with
validation for correctness. Backslashed-escaped digits (\1 to \9) within the
replacement argument can be used to insert text matching the corresponding
parenthesized group in the regexp pattern. An error will be thrown for invalid
regex or replace string.

```
regex.replace(target: string, pattern: string, replacement: string) -> string
regex.replace(target: string, pattern: string, replacement: string, count: int) -> string
```

Examples:

```
regex.replace('hello world hello', 'hello', 'hi') == 'hi world hi'
regex.replace('banana', 'a', 'x', 0) == 'banana'
regex.replace('banana', 'a', 'x', 1) == 'bxnana'
regex.replace('banana', 'a', 'x', 2) == 'bxnxna'
regex.replace('banana', 'a', 'x', -12) == 'bxnxnx'
regex.replace('foo bar', '(fo)o (ba)r', '\\2 \\1') == 'ba fo'

regex.replace('test', '(.)', '$2') \\ Runtime Error invalid replace string
regex.replace('foo bar', '(', '$2 $1') \\ Runtime Error invalid regex string
regex.replace('id=123', 'id=(?P<value>\\\\d+)', 'value: \\values') \\ Runtime Error invalid replace string

```

### Extract

The `regex.extract` function returns the first match of a regex pattern in a
string. If no match is found, it returns an optional none value. An error will
be thrown for invalid regex or for multiple capture groups.

```
regex.extract(target: string, pattern: string) -> optional<string>
```

Examples:

```
regex.extract('hello world', 'hello(.*)') == optional.of(' world')
regex.extract('item-A, item-B', 'item-(\\w+)') == optional.of('A')
regex.extract('HELLO', 'hello') == optional.empty()

regex.extract('testuser@testdomain', '(.*)@([^.]*)')) \\ Runtime Error multiple extract group
```

### Extract All

The `regex.extractAll` function returns a list of all matches of a regex
pattern in a target string. If no matches are found, it returns an empty list.
An error will be thrown for invalid regex or for multiple capture groups.

```
regex.extractAll(target: string, pattern: string) -> list<string>
```

Examples:

```
regex.extractAll('id:123, id:456', 'id:\\d+') == ['id:123', 'id:456']
regex.extractAll('id:123, id:456', 'assa') == []

regex.extractAll('testuser@testdomain', '(.*)@([^.]*)') \\ Runtime Error multiple capture group
```

## Comprehensions

TwoVarComprehensions introduces support for two-variable comprehensions.

The two-variable form of comprehensions looks similar to the one-variable
counterparts. Where possible, the same macro names were used and additional
macro signatures added. The notable distinction for two-variable comprehensions
is the introduction of `transformList`, `transformMap`, and `transformMapEntry`
support for list and map types rather than the more traditional `map` and
`filter` macros.

### All

Comprehension which tests whether all elements in the list or map satisfy a
given predicate. The `all` macro evaluates in a manner consistent with logical
AND and will short-circuit when encountering a `false` value.

    <list>.all(indexVar, valueVar, <predicate>) -> bool
    <map>.all(keyVar, valueVar, <predicate>) -> bool

Examples:

    [1, 2, 3].all(i, j, i < j) // returns true
    {'hello': 'world', 'taco': 'taco'}.all(k, v, k != v) // returns false

    // Combines two-variable comprehension with single variable
    {'h': ['hello', 'hi'], 'j': ['joke', 'jog']}
      .all(k, vals, vals.all(v, v.startsWith(k))) // returns true

### Exists

Comprehension which tests whether any element in a list or map exists which
satisfies a given predicate. The `exists` macro evaluates in a manner consistent
with logical OR and will short-circuit when encountering a `true` value.

    <list>.exists(indexVar, valueVar, <predicate>) -> bool
    <map>.exists(keyVar, valueVar, <predicate>) -> bool

Examples:

    {'greeting': 'hello', 'farewell': 'goodbye'}
      .exists(k, v, k.startsWith('good') || v.endsWith('bye')) // returns true
    [1, 2, 4, 8, 16].exists(i, v, v == 1024 && i == 10) // returns false

### Exists_One

Comprehension which tests whether exactly one element in a list or map exists
which satisfies a given predicate expression. The `exists_one` macro
comprehension does not short-circuit in keeping with the one-variable semantics.

    <list>.existsOne(indexVar, valueVar, <predicate>)
    <map>.existsOne(keyVar, valueVar, <predicate>)

Examples:

    [1, 2, 1, 3, 1, 4].existsOne(i, v, i == 1 || v == 1) // returns false
    [1, 1, 2, 2, 3, 3].existsOne(i, v, i == 2 && v == 2) // returns true
    {'i': 0, 'j': 1, 'k': 2}.existsOne(i, v, i == 'l' || v == 1) // returns true

### TransformList

Comprehension which converts a map or a list into a list value. The output
expression of the comprehension determines the contents of the output list.
Elements in the list may optionally be filtered according to a predicate
expression, where elements that satisfy the predicate are transformed.

    <list>.transformList(indexVar, valueVar, <transform>)
    <list>.transformList(indexVar, valueVar, <filter>, <transform>)
    <map>.transformList(keyVar, valueVar, <transform>)
    <map>.transformList(keyVar, valueVar, <filter>, <transform>)

Examples:

    [1, 2, 3].transformList(indexVar, valueVar,
      (indexVar * valueVar) + valueVar) // returns [1, 4, 9]
    [1, 2, 3].transformList(indexVar, valueVar, indexVar % 2 == 0
      (indexVar * valueVar) + valueVar) // returns [1, 9]
    {'greeting': 'hello', 'farewell': 'goodbye'}
      .transformList(k, _, k) // returns ['greeting', 'farewell']
    {'greeting': 'hello', 'farewell': 'goodbye'}
      .transformList(_, v, v) // returns ['hello', 'goodbye']

### TransformMap

Comprehension which converts a map or a list into a map value. The output
expression of the comprehension determines the value of the output map entry;
however, the key remains fixed. Elements in the map may optionally be filtered
according to a predicate expression, where elements that satisfy the predicate
are transformed.

    <list>.transformMap(indexVar, valueVar, <transform>)
    <list>.transformMap(indexVar, valueVar, <filter>, <transform>)
    <map>.transformMap(keyVar, valueVar, <transform>)
    <map>.transformMap(keyVar, valueVar, <filter>, <transform>)

Examples:

    [1, 2, 3].transformMap(indexVar, valueVar,
      (indexVar * valueVar) + valueVar) // returns {0: 1, 1: 4, 2: 9}
    [1, 2, 3].transformMap(indexVar, valueVar, indexVar % 2 == 0
      (indexVar * valueVar) + valueVar) // returns {0: 1, 2: 9}
    {'greeting': 'hi'}.transformMap(k, v, v + '!') // returns {'greeting': 'hi!'}

### TransformMapEntry

Comprehension which converts a map or a list into a map value; however, this
transform expects the entry expression be a map literal. If the transform
produces an entry which duplicates a key in the target map, the comprehension
will error. Note, that key equality is determined using CEL equality which
asserts that numeric values which are equal, even if they don't have the same
type will cause a key collision.

Elements in the map may optionally be filtered according to a predicate
expression, where elements that satisfy the predicate are transformed.

    <list>.transformMapEntry(indexVar, valueVar, <transform>)
    <list>.transformMapEntry(indexVar, valueVar, <filter>, <transform>)
    <map>.transformMapEntry(keyVar, valueVar, <transform>)
    <map>.transformMapEntry(keyVar, valueVar, <filter>, <transform>)

Examples:

    {'greeting': 'hello'}.transformMapEntry(keyVar, valueVar,
      {valueVar: keyVar}) // returns {'hello': 'greeting'}
    // reverse lookup, require all values in list be unique
    [1, 2, 3].transformMapEntry(indexVar, valueVar,
      {valueVar: indexVar}) // returns {1:0, 2:1, 3:2}

    {'greeting': 'aloha', 'farewell': 'aloha'}
      .transformMapEntry(k, v, {v: k}) // error, duplicate key

## Native Types

The `nativeTypes` extension allows registering native Java types (POJOs) to be
used in CEL expressions.

All POJO classes are exposed to CEL using their fully qualified canonical name.
For example, if you have a class `com.example.Account`:

```java
package com.example;
public class Account {
  public int id;
}
```

The type `com.example.Account` would be exported to CEL using its full name. If
you set the container to `com.example` on the compiler, you can use it simply
as `Account`: `Account{id: 1234}` would create a new `Account` instance with the
`id` field populated.

Properties are discovered by reflectively scanning public fields and public
getter methods of public classes. For field selection (reading) and object
creation (writing), resolution happens in the following order of precedence:

1.  Standard JavaBeans getter (e.g., `getFoo()`) or setter (e.g., `setFoo(...)`)
2.  Boolean getter (e.g., `isFoo()`) for boolean properties
3.  Prefix-less getter (e.g., `foo()`) matching a declared field name
4.  Public field directly (e.g., `public String foo`)

### Type Mapping

The type-mapping between Java and CEL is as follows:

| Java type | CEL type |
| :--- | :--- |
| `boolean`, `Boolean` | `bool` |
| `byte[]` | `bytes` |
| `float`, `Float`, `double`, `Double` | `double` |
| `int`, `Integer`, `long`, `Long` | `int` |
| `com.google.common.primitives.UnsignedLong` | `uint` |
| `String` | `string` |
| `java.time.Duration` | `duration` |
| `java.time.Instant` | `timestamp` |
| `java.util.List`, `T[]` (except `byte[]`) | `list` |
| `java.util.Map` | `map` |
| `java.util.Optional` | `optional_type` |

### Notes

*   This is only supported for the planner runtime (e.g., `CelRuntimeFactory.plannerRuntimeBuilder()`).
*   Native Java arrays are supported. `byte[]` maps to `bytes`, while other arrays map to `list`.
*   Java `enum` properties are not currently supported and will be safely ignored during scanning.
*   If there is a name collision with a Protobuf type, the protobuf type will take precedence.
*   Instantiating new struct values (e.g., `Account{id: 1234}`) requires the class to have a no-argument constructor (public, protected, package-private, or private).
*   Final fields are supported only in a **read-only** capacity; they cannot be populated when instantiating new struct values.
