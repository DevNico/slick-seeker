# API Reference

## Profile Setup

### SlickSeekerSupport

Trait that adds cursor-based pagination support to any Slick JDBC profile.

```scala
trait SlickSeekerSupport { driver: JdbcProfile =>
  // Extension methods and exports
}
```

**Usage:**

Mix this trait with any Slick JDBC profile to add `.toSeeker` extension method:

```scala
// PostgreSQL
trait MyPostgresProfile extends slick.jdbc.PostgresProfile with SlickSeekerSupport
object MyPostgresProfile extends MyPostgresProfile

// MySQL  
trait MyMySQLProfile extends slick.jdbc.MySQLProfile with SlickSeekerSupport
object MyMySQLProfile extends MyMySQLProfile

// H2
trait MyH2Profile extends slick.jdbc.H2Profile with SlickSeekerSupport
object MyH2Profile extends MyH2Profile
```

Then import your profile's API:

```scala
import MyPostgresProfile.api._
```

This provides:
- All standard Slick API methods
- `.toSeeker` extension method on `Query`
- All necessary type classes and implicits

## SlickSeeker

Main class for cursor-based pagination.

```scala
case class SlickSeeker[E, U, CVE, C, CU](
  baseQuery: Query[E, U, Seq],
  columns: Vector[SeekColumn[E, U, ?, CVE]],
  qwc: QueryWithCursor[E, U, C, CU, CVE]
)
```

### Methods

#### seek

Add a sort column with automatic cursor extraction.

```scala
def seek[T](col: E => ColumnOrdered[T])(using
  sk: SeekerSortKey[T, CVE]
): SlickSeeker[E, U, CVE, (Rep[sk.Key], C), (sk.Key, CU)]
```

**Parameters:**
- `col`: Function to extract the column to sort by

**Returns:** New seeker with the column added

**Example:**
```scala
users.toSeeker
  .seek(_.name.asc)
  .seek(_.id.asc)
```

#### seekDirection

Set the sort direction for all columns.

```scala
def seekDirection(direction: SortDirection): SlickSeeker[E, U, CVE, C, CU]
```

**Parameters:**
- `direction`: `SortDirection.Asc` or `SortDirection.Desc`

**Returns:** New seeker with reversed direction

**Example:**
```scala
users.toSeeker
  .seek(_.name)
  .seek(_.id)
  .seekDirection(SortDirection.Desc)
```

#### page

Execute pagination query.

```scala
def page(
  limit: Int,
  cursor: Option[String],
  maxLimit: Int = 100
)(using
  cursorEnvironment: CursorEnvironment[CVE],
  ec: ExecutionContext
): DBIOAction[PaginatedResult[U], NoStream, Effect.Read]
```

**Parameters:**
- `limit`: Number of items per page (clamped to `[1, maxLimit]`)
- `cursor`: Cursor from previous page (`None` for first page)
- `maxLimit`: Maximum allowed limit (default: 100)

**Returns:** Database action that yields `PaginatedResult[U]`

**Example:**
```scala
db.run(seeker.page(limit = 20, cursor = None))
```

## PaginatedResult

Result of a pagination query.

```scala
case class PaginatedResult[T](
  total: Int,
  items: Seq[T],
  nextCursor: Option[String],
  prevCursor: Option[String]
)
```

**Fields:**
- `total`: Total number of items (across all pages)
- `items`: Items for current page
- `nextCursor`: Cursor for next page (`None` if last page)
- `prevCursor`: Cursor for previous page (`None` if first page)

## CursorEnvironment

Environment for cursor encoding/decoding.

```scala
case class CursorEnvironment[E](
  codec: CursorCodec[E],
  decorator: CursorDecorator
)
```

**Methods:**

### encode

```scala
def encode(values: Seq[E], direction: CursorDirection): String
```

Encode cursor values with direction.

### decode

```scala
def decode(cursor: Option[String]): Either[DecodeError, Option[Seq[E]]]
```

Decode cursor string to values.

### decodeWithDirection

```scala
def decodeWithDirection(
  cursor: Option[String]
): Either[DecodeError, Option[(CursorDirection, Seq[E])]]
```

Decode cursor with direction information.

### decodeOrThrow

```scala
def decodeOrThrow(cursor: Option[String]): Option[Seq[E]]
```

Decode cursor or throw `IllegalArgumentException`.

## CursorCodec

Codec for cursor value sequences.

```scala
trait CursorCodec[E] {
  def encode(values: Seq[E]): String
  def decode(cursor: String): Either[String, Seq[E]]
}
```

## CursorValueCodec

Codec for individual cursor values.

```scala
trait CursorValueCodec[T, E] {
  def encode(value: T): E
  def decode(value: E): Option[T]
}
```

## CursorDecorator

Decorator for final cursor encoding.

```scala
trait CursorDecorator {
  def encode(value: String): String
  def decode(cursor: String): Either[Throwable, String]
}
```

### Built-in Decorators

#### IdentityDecorator

No transformation - pass-through.

```scala
class IdentityDecorator extends CursorDecorator
```

#### Base64Decorator

Base64 encoding.

```scala
class Base64Decorator(inner: CursorDecorator = IdentityDecorator()) 
  extends CursorDecorator
```

**Example:**
```scala
// Single decorator
Base64Decorator()

// Chained decorators
Base64Decorator(GzipDecorator())
```

## SeekOrder

Custom sort order for enums/ADTs.

```scala
case class SeekOrder[T](values: IndexedSeq[T])
```

**Methods:**

### index

```scala
def index(value: T): Int
```

Get the index of a value in the sort order.

**Example:**
```scala
enum Status {
  case Pending, Active, Completed
}

given SeekOrder[Status] = SeekOrder(IndexedSeq(
  Status.Pending, Status.Active, Status.Completed
))

val idx = summon[SeekOrder[Status]].index(Status.Active)  // 1
```

## SortDirection

Sort direction enum.

```scala
enum SortDirection {
  case Asc, Desc
}
```

## Extension Methods

### toSeeker

Convert a Slick query to a seeker.

```scala
extension [E, U](query: Query[E, U, Seq]) {
  def toSeeker[CVE](using
    CursorEnvironment[CVE],
    Shape[FlatShapeLevel, E, U, E],
    BaseTypedType[Int]
  ): SlickSeeker[E, U, CVE, Rep[Int], Int]
}
```

**Example:**
```scala
import io.github.devnico.slickseeker._

val seeker = users.toSeeker
```

## Play JSON Support

### PlayJsonSupport

Provides Play JSON integration.

```scala
object PlayJsonSupport
```

### cursorEnvironment

Create cursor environment with Play JSON.

```scala
def cursorEnvironment(
  decorator: CursorDecorator = Base64Decorator()
): CursorEnvironment[JsValue]
```

**Example:**
```scala
import io.github.devnico.slickseeker.playjson._

trait MyProfile extends PostgresProfile with SlickSeekerSupport {
  implicit val cursorEnv: CursorEnvironment[JsValue] = 
    PlayJsonSupport.cursorEnvironment(Base64Decorator())
}
```

### defaultJsonCursorEnvironment

Default cursor environment (JSON + Base64).

```scala
given defaultJsonCursorEnvironment: CursorEnvironment[JsValue]
```

**Example:**
```scala
import io.github.devnico.slickseeker.playjson._

trait MyProfile extends PostgresProfile with SlickSeekerSupport {
  // Uses default environment
  implicit val cursorEnv: CursorEnvironment[JsValue] = 
    PlayJsonSupport.defaultJsonCursorEnvironment
}
```

### Given Instances

#### jsonCursorValueCodec

Codec for Play JSON values.

```scala
given jsonCursorValueCodec[T: Format]: CursorValueCodec[T, JsValue]
```

#### jsonOptionCursorValueCodec

Codec for optional Play JSON values.

```scala
given jsonOptionCursorValueCodec[T: Format]: CursorValueCodec[Option[T], JsValue]
```

#### paginatedResultFormat

JSON format for `PaginatedResult`.

```scala
given paginatedResultFormat[T: Format]: Format[PaginatedResult[T]]
```

**Example:**
```scala
import io.github.devnico.slickseeker.playjson._
import play.api.libs.json._

given Format[User] = Json.format[User]

val result: PaginatedResult[User] = ???
val json = Json.toJson(result)
```

#### sortDirectionFormat

JSON format for `SortDirection`.

```scala
given sortDirectionFormat: Format[SortDirection]
```

Accepts: `"asc"`, `"Asc"`, `"ASC"`, `"desc"`, `"Desc"`, `"DESC"`

## Type Parameters

### SlickSeeker Type Parameters

- `E`: Table/query element type
- `U`: Unpacked row type (result type)
- `CVE`: Cursor value encoding type (e.g., `JsValue`, `String`)
- `C`: Cursor column projection (accumulates with each `seek`)
- `CU`: Unpacked cursor type (accumulates with each `seek`)

**Example:**

```scala
// Initial
SlickSeeker[Persons, Person, JsValue, Rep[Int], Int]

// After .seek(_.name)
SlickSeeker[Persons, Person, JsValue, (Rep[String], Rep[Int]), (String, Int)]

// After .seek(_.id)
SlickSeeker[Persons, Person, JsValue, (Rep[Int], (Rep[String], Rep[Int])), (Int, (String, Int))]
```

The type parameters ensure cursor values match your sort columns at compile time.
