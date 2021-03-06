

Data Types
==========

openLooKeng has a set of built-in data types, described below. Additional types can be provided by plugins.


**Note**

*Connectors are not required to support all types. See [Connectors](../connector/_index.md) for details on supported types.*

Boolean
-------

### `BOOLEAN`

> This type captures boolean values `true` and `false`.

Integer
-------

### `TINYINT`

> A 8-bit signed two\'s complement integer with a minimum value of `-2^7` and a maximum value of `2^7 - 1`.

### `SMALLINT`

> A 16-bit signed two\'s complement integer with a minimum value of `-2^15` and a maximum value of `2^15 - 1`.

### `INTEGER`

> A 32-bit signed two\'s complement integer with a minimum value of `-2^31` and a maximum value of `2^31 - 1`. The name `INT` is also available for this type.

### `BIGINT`

> A 64-bit signed two\'s complement integer with a minimum value of `-2^63` and a maximum value of `2^63 - 1`.

Floating-Point
--------------

### `REAL`

> A real is a 32-bit inexact, variable-precision implementing the IEEE Standard 754 for Binary Floating-Point Arithmetic.
>
> The name `FLOAT` is also available for this type.
>
> Example: REAL '10.3', REAL '10.3e0', REAL '1.03e1'

### `DOUBLE`

> A double is a 64-bit inexact, variable-precision implementing the IEEE Standard 754 for Binary Floating-Point Arithmetic.
>
> The name `DOUBLE PRECISION` is also available for this type.

Fixed-Precision
---------------

### `DECIMAL`

> A fixed precision decimal number. Precision up to 38 digits is supported but performance is best up to 18 digits.
>
> The name `NUMERIC` and `DEC` is also available for this type.
>
> The decimal type takes two literal parameters:
>
> -   **precision** - total number of digits
> -   **scale** - number of digits in fractional part. Scale is optional and defaults to 0.
>
> Example type definitions: `DECIMAL(10,3)`, `DECIMAL(20)`
>
> Example literals: `DECIMAL '10.3'`, `DECIMAL '1234567890'`, `1.1`
>
>
> **Note**
>
> For compatibility reasons decimal literals without explicit type specifier (e.g. `1.2`) are treated as the values of the `DOUBLE` type by default, but this is subject to change in future releases. This
> behavior is controlled by:
>
> -   System wide property: `parse-decimal-literals-as-double`
> -   Session wide property: `parse_decimal_literals_as_double`

String
------

### `VARCHAR`

> Variable length character data with an optional maximum length.
>
> The name `STRING` is also available for this type. Please be advised, `STRING` defined an unlimited length character data, you should not specify the length, otherwise it will be same as `VARCHAR(length)`.
>
> Example type definitions: `varchar`, `varchar(20)`, `string`
>
> SQL statements support simple literal, as well as Unicode usage:
>
> -   literal string : `'Hello winter !'`
> -   Unicode string with default escape character: `U&'Hello winter \2603 !'`
> -   Unicode string with custom escape character: `U&'Hello winter #2603 !' UESCAPE '#'`
>
> A Unicode string is prefixed with `U&` and requires an escape
> character before any Unicode character usage with 4 digits. In the
> examples above `\2603` and `#2603` represent a snowman character. Long
> Unicode codes with 6 digits require usage of the plus symbol before
> the code. For example, you need to use `\+01F600` for a grinning face
> emoji.

### `CHAR`

> Fixed length character data. A `CHAR` type without length specified has a default length of 1. A `CHAR(x)` value always has `x` characters. For instance, casting `dog` to `CHAR(7)` adds 4 implicit trailing spaces. Leading and trailing spaces are included in comparisons of `CHAR` values. As a result, two character values with different lengths (`CHAR(x)` and `CHAR(y)` where `x != y`) will never
> be equal.
>
> Example type definitions: `char`, `char(20)`

### `VARBINARY`

> Variable length binary data.
>
> 
> **Note**
> 
> Binary strings with length are not yet supported: `varbinary(n)`
>

### `JSON`

> JSON value type, which can be a JSON object, a JSON array, a JSON number, a JSON string, `true`, `false` or `null`.

Date and Time
-------------

### `DATE`

> Calendar date (year, month, day).
>
> Example: `DATE '2001-08-22'`

### `TIME`

> Time of day (hour, minute, second, millisecond) without a time zone. Values of this type are parsed and rendered in the session time zone.
>
> Example: `TIME '01:02:03.456'`

### `TIME WITH TIME ZONE`

> Time of day (hour, minute, second, millisecond) with a time zone. Values of this type are rendered using the time zone from the value.
>
> Example: `TIME '01:02:03.456 America/Los_Angeles'`

### `TIMESTAMP`

> Instant in time that includes the date and time of day without a time zone. Values of this type are parsed and rendered in the session time zone.
>
> Example: `TIMESTAMP '2001-08-22 03:04:05.321'`

### `TIMESTAMP WITH TIME ZONE`

> Instant in time that includes the date and time of day with a time zone. Values of this type are rendered using the time zone from the value.
>
> Example: `TIMESTAMP '2001-08-22 03:04:05.321 America/Los_Angeles'`

### `INTERVAL YEAR TO MONTH`

> Span of years and months.
>
> Example: `INTERVAL '3' MONTH`

### `INTERVAL DAY TO SECOND`

> Span of days, hours, minutes, seconds and milliseconds.
>
> Example: `INTERVAL '2' DAY`

Structural
----------

### `ARRAY`

> An array of the given component type.
>
> Example: `ARRAY[1, 2, 3]`


### `MAP`

> A map between the given component types.
>
> Example: `MAP(ARRAY['foo', 'bar'], ARRAY[1, 2])`

### `ROW`

> A structure made up of fields that allows mixed types. The fields may be of any SQL type.
>
> By default, row fields are not named, but names can be assigned.
>
> Example: `CAST(ROW(1, 2.0) AS ROW(x BIGINT, y DOUBLE))`
>
> Named row fields are accessed with field reference operator `.`.
>
> Example: `CAST(ROW(1, 2.0) AS ROW(x BIGINT, y DOUBLE)).x`
>
> Named or unnamed row fields are accessed by position with the subscript operator `[]`. The position starts at `1` and must be a constant.
>
> Example: `ROW(1, 2.0)[1]`

Network Address
---------------

### `IPADDRESS`

> An IP address that can represent either an IPv4 or IPv6 address. Internally, the type is a pure IPv6 address. Support for IPv4 is handled using the *IPv4-mapped IPv6 address* range ([RFC 4291#section-2.5.5.2](https://tools.ietf.org/html/rfc4291.html#section-2.5.5.2)). When creating an `IPADDRESS`, IPv4 addresses will be mapped into that range. When formatting an `IPADDRESS`, any address within the mapped range will be formatted as an IPv4 address. Other addresses will be formatted as IPv6 using the canonical format defined in [RFC 5952](https://tools.ietf.org/html/rfc5952.html ).
>
> Examples: `IPADDRESS '10.0.0.1'`, `IPADDRESS '2001:db8::1'`

UUID
----

### `UUID`

> This type represents a UUID (Universally Unique IDentifier), also known as a GUID (Globally Unique IDentifier), using the format defined in [RFC 4122](https://tools.ietf.org/html/rfc4122.html).
>
> Example: `UUID '12151fd2-7586-11e9-8f9e-2a86e4085a59'`

HyperLogLog
-----------

Calculating the approximate distinct count can be done much more cheaply than an exact count using the
[HyperLogLog](https://en.wikipedia.org/wiki/HyperLogLog) data sketch. See [HyperLogLog Functions](../functions/hyperloglog.md).

### `HyperLogLog`

> A HyperLogLog sketch allows efficient computation of [approx_distinct()](../functions/aggregate.md). It starts as a sparse representation, switching to a dense representation when it becomes more efficient.

### `P4HyperLogLog`

> A P4HyperLogLog sketch is similar to `hyperloglog_type`, but it starts (and remains) in the dense representation.

Quantile Digest
---------------

### `QDigest`

> A quantile digest (qdigest) is a summary structure which captures the approximate distribution of data for a given input set, and can be queried to retrieve approximate quantile values from the distribution.
> The level of accuracy for a qdigest is tunable, allowing for more precise results at the expense of space.
>
> A qdigest can be used to give approximate answer to queries asking for what value belongs at a certain quantile. A useful property of qdigests is that they are additive, meaning they can be merged together without losing precision.
>
> A qdigest may be helpful whenever the partial results of `approx_percentile` can be reused. For example, one may be interested in a daily reading of the 99th percentile values that are read over
> the course of a week. Instead of calculating the past week of data with `approx_percentile`, `qdigest`s could be stored daily, and quickly merged to retrieve the 99th percentile value.
