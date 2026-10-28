# Core Concepts

## Cursor-Based Pagination

Slick Seeker implements **keyset pagination**, which uses the values from the last row as a cursor to find the next page.

### How It Works

Instead of using `OFFSET`:

```sql
-- Slow for deep pages
SELECT * FROM users ORDER BY name LIMIT 20 OFFSET 1000;
```

We use the last row's values:

```sql
-- Fast for any page
SELECT * FROM users 
WHERE name > 'last_name' OR (name = 'last_name' AND id > last_id)
ORDER BY name, id 
LIMIT 20;
```

## seek()

Use `seek()` for direct column references.

```scala
// Direct columns - automatic extraction
val seeker = users.toSeeker
  .seek(_.name.asc)
  .seek(_.id.asc)
```

Works with:

- Table columns: `_.name`, `_.email`
- Tuple fields: `_._1`, `_._2`
- Nested tuples: `_._1._2`
- Any computed column: `_.name.toLowerCase`

The SQL looks like:

```sql
SELECT t.*, t.name, t.id  -- Cursor columns added automatically
FROM users t
WHERE t.name > ? OR (t.name = ? AND t.id > ?)
ORDER BY t.name ASC, t.id ASC
LIMIT 20
```

### Cursor Values as Parameters

Cursor values are bound as parameters of the column's type, so they compare exactly like the column's own values
(a `REAL` column's `0.1f` equals its cursor value, which the literal `0.1` would not). Cursor value codecs should
therefore decode the column's type; values of another type are written as literals, as in earlier versions.
Floating-point seek columns stay fragile where the driver sends parameters as text (MariaDB Connector/J by default,
see [Supported Databases](#supported-databases)); prefer `DECIMAL` columns or another seek column there.

- On SQL Server, a cursor value only compares like its column sorts if it has the column's type, and the driver
  binds strings as `nvarchar` and timestamps as `datetime2`. Seekers therefore cast cursor values of string
  columns to `VARCHAR(MAX)` or `NVARCHAR(MAX)` and of `DATETIME` / `SMALLDATETIME` columns to their type, after the
  column's declared type: its `O.SqlType`, else the type Slick's DDL gives it (`VARCHAR` for strings,
  `DATETIME2(6)` for timestamps). Declare `O.SqlType` on seek columns whose database type differs, e.g.
  `column[String]("name", O.SqlType("NVARCHAR(100)"))` or `column[LocalDateTime]("at", O.SqlType("DATETIME"))`.
  An expression gets the declared type of its columns of the same type (`_.name.toLowerCase`: `name`'s). Values of
  an expression over columns of different declared types, or of a column of a query the seeker can't follow to its
  table, are not cast: they compare as the driver binds them (`nvarchar`, `datetime2`).
  Override `seekerCursorValueCast` in your profile to change the casts.

- On PostgreSQL, string-backed cursor values (`String` and types mapped to `String`) are bound untyped, as literals
  would be, so they compare with native enums, `uuid` and other columns mapped to `String`. Override
  `seekerUntypedStringParams` in your profile to change it.
- With parameters, every page of a seeker has the same SQL, so the database may reuse a generic plan (PostgreSQL does
  after five executions of a server-side prepared statement). Check the plans of hot page queries;
  `plan_cache_mode = force_custom_plan` restores planning per cursor value.
- The settings (`seekerRowComparisons`, `seekerUntypedStringParams`, `seekerCursorValueCast`, `seekerDefaultNulls`)
  are read from the profile a page runs with: the implicit profile of `page` / `pageWithoutCount`, or the profile of
  a `CompiledSeekPage`.

### Row Value Comparisons

On PostgreSQL and H2, when all seek columns are non-nullable and sort in the same direction, the seeker compares them
as one row value:

```sql
WHERE (t.name, t.id) > (?, ?)   -- instead of t.name > ? OR (t.name = ? AND t.id > ?)
ORDER BY t.name ASC, t.id ASC
```

The results are the same, and the database can scan a composite index on `(name, id)` from the cursor on. Seekers
with `Option` columns, mixed directions or custom `ColumnSeekFilter`s chain their conditions instead. To turn row
value comparisons off (or on for another database), override them in your profile:

```scala
trait MyProfile extends PostgresProfile with SlickSeekerSupport {
  override def seekerRowComparisons: Boolean = false
  // …
}
```

Check the plans before turning them on elsewhere: MySQL and MariaDB support row values but don't use an index range
for them the way they do for the chained conditions, so keep them off there.

## Null Handling

Slick Seeker supports explicit null ordering:

```scala
.seek(_.lastName.nullsLast.asc)   // Nulls last
.seek(_.lastName.nullsFirst.asc)  // Nulls first

// Without a nulls clause: where the database puts nulls by default
.seek(_.lastName.asc)
```

Without a nulls clause, an `Option` column's nulls go where the database puts them by default, and the seeker writes
that ordering out explicitly so its cursor conditions match it:

| Database | `.asc` | `.desc` |
|---|---|---|
| PostgreSQL, Oracle, DB2, Derby (and unknown databases) | nulls last | nulls first |
| H2, MySQL, SQLite, SQL Server | nulls first | nulls last |
| HSQLDB | nulls first | nulls first |

This keeps the order (and the indexes serving it) the database uses anyway. If your database or its configuration
sorts nulls differently, override `seekerDefaultNulls` in your profile: e.g. H2 with `DEFAULT_NULL_ORDERING`, or
PostgreSQL-compatible databases with other defaults that use `PostgresProfile` (CockroachDB sorts nulls first
ascending, like H2). A wrong placement still pages every row exactly once, but puts null rows elsewhere than the
database would and can't use an index for the order:

```scala
trait MyProfile extends H2Profile with SlickSeekerSupport {
  // H2 with DEFAULT_NULL_ORDERING=HIGH
  override def seekerDefaultNulls(direction: slick.ast.Ordering.Direction): slick.ast.Ordering.NullOrdering =
    if (direction == slick.ast.Ordering.Asc) slick.ast.Ordering.NullsLast else slick.ast.Ordering.NullsFirst
  // …
}
```

For CockroachDB (which uses `PostgresProfile`), the reverse:

```scala
trait MyCockroachProfile extends PostgresProfile with SlickSeekerSupport {
  override def seekerDefaultNulls(direction: slick.ast.Ordering.Direction): slick.ast.Ordering.NullOrdering =
    if (direction == slick.ast.Ordering.Asc) slick.ast.Ordering.NullsFirst else slick.ast.Ordering.NullsLast
  // …
}
```

`seekDirection` keeps explicit null orderings when a column already sorts in that direction and mirrors them when it
reverses it (`.desc.nullsFirst` becomes `.asc.nullsLast`). `Option` columns without one get its own null ordering, as
in earlier versions (not the database's default):

```scala
// ASC: nulls last
.seek(_.lastName)
.seekDirection(SortDirection.Asc)

// DESC: nulls first
.seek(_.lastName)
.seekDirection(SortDirection.Desc)
```

### Complex Null Handling

```scala
case class Person(
  id: Int,
  firstName: String,
  middleName: Option[String],
  lastName: Option[String]
)

// Sort: lastName (nulls last), middleName (nulls first), firstName, id
val seeker = persons.toSeeker
  .seek(_.lastName.nullsLast.asc)
  .seek(_.middleName.nullsFirst.asc)
  .seek(_.firstName.asc)
  .seek(_.id.asc)
```

## Custom Sort Orders (Enums/ADTs)

For enums or sealed traits, define a custom sort order:

=== "Scala 3"

    ```scala
    enum Status {
      case Pending, Active, Completed, Archived
    }

    // Define the order
    given SeekOrder[Status] = SeekOrder(IndexedSeq(
      Status.Pending,
      Status.Active,
      Status.Completed,
      Status.Archived
    ))

    // Use it
    val seeker = tasks.toSeeker
      .seek(_.status.asc)  // Uses custom order
      .seek(_.id.asc)
    ```

=== "Scala 2"

    ```scala
    sealed trait Status
    object Status {
      case object Pending extends Status
      case object Active extends Status
      case object Completed extends Status
      case object Archived extends Status
    }

    // Define the order
    implicit val statusOrder: SeekOrder[Status] = SeekOrder(IndexedSeq(
      Status.Pending,
      Status.Active,
      Status.Completed,
      Status.Archived
    ))

    // Use it
    val seeker = tasks.toSeeker
      .seek(_.status.asc)  // Uses custom order
      .seek(_.id.asc)
    ```

This converts enum values to integers in SQL:

```sql
ORDER BY 
  CASE 
    WHEN status = 'Pending' THEN 0
    WHEN status = 'Active' THEN 1
    WHEN status = 'Completed' THEN 2
    WHEN status = 'Archived' THEN 3
  END ASC
```

## Supported Databases

CI tests against these databases: many seeker configurations paged forward and backward, ad-hoc and compiled, must
return each row exactly once in the database's own order.

| Database | Tested with | Profile | Notes |
|---|---|---|---|
| PostgreSQL | 16 | `PostgresProfile` | Row value comparisons; untyped string parameters (enums, `uuid`) |
| H2 | 2.4 | `H2Profile` | Row value comparisons |
| HSQLDB | 2.7 | `HsqldbProfile` | |
| SQLite | 3.53 | `SQLiteProfile` | |
| Derby | 10.17 | `DerbyProfile` | |
| MySQL | 8.4 (Connector/J 9) | `MySQLProfile` | |
| MariaDB | 11.4 (MariaDB Connector/J 3.5) | `MySQLProfile` | See floating-point columns below |
| SQL Server | 2022 (mssql-jdbc 13) | `SQLServerProfile` | Declare `O.SqlType` where it differs from Slick's ([casts](#cursor-values-as-parameters)) |
| Oracle | 23 (Free) | `OracleProfile` | |
| CockroachDB | 25.4 | `PostgresProfile` | Needs the [nulls override](#null-handling) |

`sbt test` covers H2, HSQLDB and SQLite; the others run in containers (Docker) when selected, e.g. one at a time:
`SEEKER_DATABASES=sqlserver sbt "core/testOnly io.github.devnico.slickseeker.databases.*"` (ids: `derby`,
`postgresql`, `mysql`, `mariadb`, `sqlserver`, `oracle`, `cockroachdb`, or `all`).

Known limitations:

- **MariaDB, floating-point seek columns**: MariaDB Connector/J sends parameters as text by default, so a cursor
  value `0.1` never equals the `REAL` value `0.1f` it came from and pages repeat. Connect with
  `useServerPrepStmts=true`, or seek on `DECIMAL` columns. MySQL with Connector/J pages them correctly.
- **SQL Server, `sendStringParametersAsUnicode=false`**: `varchar` and `datetime` columns still page correctly, but
  `nvarchar` values outside the database's code page are lost before any cast: keep the default (`true`). The same
  goes for a `varchar` column whose collation has another code page than the database default.
  `datetimeParameterType=datetime` loses `datetime2` precision the same way.
- **SQL Server, `text` / `ntext`** columns can't be sorted, so they can't be seek columns.
- **MySQL / MariaDB, row value comparisons** are off: they don't use an index range for them (see
  [Row Value Comparisons](#row-value-comparisons)).

## Pagination Without Count

By default, `page()` runs a `COUNT(*)` query to include the total number of matching rows. Use `pageWithoutCount()` to skip it:

```scala
// With count (runs COUNT(*) + data query)
val result: PaginatedResult[User] =
  db.run(seeker.page(limit = 20, cursor = None))

// Without count (runs data query only — faster)
val result: PaginatedResultWithoutCount[User] =
  db.run(seeker.pageWithoutCount(limit = 20, cursor = None))
```

Both methods produce interoperable cursors. You can convert between result types:

```scala
val withoutCount: PaginatedResultWithoutCount[User] = result.withoutCount
val withCount: PaginatedResult[User] = withoutCountResult.withCount(total)
```

## Compiled Pages

Slick compiles every query to SQL before running it, which can cost more CPU than the database spends on it,
especially for joined queries. A seeker builds a new query for every page, so Slick compiles each page again and its
pages cannot be precompiled with `Compiled`. `CompiledSeekPage` binds the application parameters, cursor values and
limit as parameters of queries compiled once instead:

```scala
// Created once (e.g. a val), reused for every request
val byMinAge = CompiledSeekPage[Int]((minAge: ConstColumn[Int]) =>
  persons.filter(_.age >= minAge).toSeeker
    .seek(_.lastName.desc.nullsLast)
    .seek(_.id.asc)
)(p => p.id)

byMinAge.pageWithoutCount(params = 30, limit = 20, cursor = None)   // or .page(…) with a total count
```

- The application parameters (`AU`, here `Int`) are passed per call; `build` receives them as `Rep`s. The query may
  only depend on these parameters: everything else (constants, table and schema names, Scala-side branches) is
  compiled in. Optional filters need either one `CompiledSeekPage` per combination or SQL-side handling
  (`p.isEmpty || …`).
- Each form of the page query is compiled once: the first page and, for forward and backward pages, one per
  combination of `None` values in nullable cursor columns (`maxForms` = 1 + 2 × 2^nullable columns).
- Items and cursors are the same as those of `pageWithoutCountAs` / `pageAs` for every cursor the seeker produced,
  including row value comparisons. A cursor that can't be decoded at all fails like it does for the ad-hoc seeker;
  one whose values don't decode or don't fit the cursor columns gives the first page (the ad-hoc seeker then pages
  without a cursor filter, or with one on the leading columns if there are fewer values than columns).
- Cursor value codecs must decode values of the column's type (checked per page). Custom `SeekerSortKey`s and
  `ColumnSeekFilter`s need `typedType` and `applyParam` (the built-in ones have them); a missing one fails when the
  `CompiledSeekPage` is created.
- Generic plans: see [Cursor Values as Parameters](#cursor-values-as-parameters).
- The seeker needs at least one seek column.
- `SlickPgTupleSeeker` is not supported; `toSeeker` seekers use the same row value comparisons where they qualify.

## Bidirectional Pagination

Slick Seeker supports both forward and backward navigation with both `page()` and `pageWithoutCount()`:

```scala
// Forward
val page2 = seeker.page(limit = 20, cursor = page1.nextCursor)

// Backward
val backToPage1 = seeker.page(limit = 20, cursor = page2.prevCursor)
```

### How It Works

Cursors encode direction:

- Forward: `>` prefix
- Backward: `<` prefix

For backward pagination:

1. Sort order is reversed
2. Results are retrieved
3. Results are reversed back to original order

This ensures consistent ordering regardless of direction.

## Cursor Environment

The cursor environment controls how cursors are encoded. Define it inside your profile:

```scala
import io.github.devnico.slickseeker.playjson.PlayJsonSeekerSupport

trait MyPostgresProfile extends PostgresProfile 
  with SlickSeekerSupport 
  with PlayJsonSeekerSupport {
  object MyApi extends JdbcAPI with SeekImplicits with JsonSeekerImplicits
  override val api: MyApi.type = MyApi
}
```

Components:

- **Codec**: Serializes values (e.g., JSON)
- **Decorator**: Transforms final string (e.g., Base64)

## Decorators

Decorators transform cursor strings. Chain them for multiple transformations:

```scala
trait MyProfile extends PostgresProfile 
  with SlickSeekerSupport 
  with PlayJsonSeekerSupport {
  
  object MyApi extends JdbcAPI with SeekImplicits with JsonSeekerImplicits {
    // Override with custom decorator
    override implicit val cursorEnvironment: CursorEnvironment[JsValue] = 
      CursorEnvironment(jsonCursorCodec, IdentityDecorator())
  }
  
  override val api: MyApi.type = MyApi
}
```

### Custom Decorator

```scala
class HexDecorator(inner: CursorDecorator = IdentityDecorator()) 
  extends CursorDecorator {
  
  override def encode(value: String): String = {
    val innerEncoded = inner.encode(value)
    innerEncoded.getBytes.map("%02x".format(_)).mkString
  }
  
  override def decode(cursor: String): Either[Throwable, String] = {
    Try {
      val bytes = cursor.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
      new String(bytes)
    }.toEither.flatMap(inner.decode)
  }
}

// Use it
trait MyProfile extends PostgresProfile 
  with SlickSeekerSupport 
  with PlayJsonSeekerSupport {
  
  object MyApi extends JdbcAPI with SeekImplicits with JsonSeekerImplicits {
    override implicit val cursorEnvironment: CursorEnvironment[JsValue] =
      CursorEnvironment(jsonCursorCodec, Base64Decorator(HexDecorator()))
  }
  
  override val api: MyApi.type = MyApi
}
```

Decorators are useful for:

- **Base64** - URL-safe encoding
- **Compression** - Reduce cursor size
- **Encryption** - Hide cursor content
- **Signing** - Prevent tampering

## Tips

### 1. Always Include a Unique Column

```scala
// BAD: Non-unique sort can miss/duplicate items
.seek(_.status)

// GOOD: Include unique tiebreaker
.seek(_.status)
.seek(_.id)
```

### 2. Create Composite Indexes

```sql
-- Match your seek columns
CREATE INDEX idx_users_name_id ON users(name, id);
CREATE INDEX idx_tasks_status_priority_id ON tasks(status, priority, id);
```

### 3. Limit Sort Columns

Each sort column adds to the WHERE clause complexity. Use 2-4 columns typically.
