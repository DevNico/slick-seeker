# Slick Seeker

Type-safe, high-performance cursor-based pagination for Slick 3.5+.

[![CI](https://github.com/DevNico/slick-seeker/actions/workflows/ci.yml/badge.svg)](https://github.com/DevNico/slick-seeker/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

## Features

- **Keyset Pagination** - O(1) performance, no OFFSET
- **Bidirectional** - Forward and backward navigation with a single query
- **Type-Safe** - Compile-time cursor/column verification
- **Flexible** - Nulls handling, custom enum orders
- **Composable** - Chain decorators for Base64, compression, encryption
- **Profile Agnostic** - Works with any Slick JDBC profile (PostgreSQL, MySQL, H2, SQLite, etc.)

## Requirements

- **Scala**: 2.13.14+, 3.3.4+, 3.5.2+
- **Slick**: 3.6.1+
- **Java**: 11, 17, 21, or 25
- Any JDBC database supported by Slick

## Installation

```scala
libraryDependencies ++= Seq(
  "io.github.devnico" %% "slick-seeker" % "0.3.3",
  "io.github.devnico" %% "slick-seeker-play-json" % "0.3.3" // Optional
)
```

## Documentation

Full documentation: https://devnico.github.io/slick-seeker

- [Quick Start](https://devnico.github.io/slick-seeker/quickstart/)
- [Core Concepts](https://devnico.github.io/slick-seeker/concepts/)
- [Cookbook](https://devnico.github.io/slick-seeker/cookbook/)

## Usage

### Step 1: Create Your Profile

Create a custom profile that extends your database profile and mixes in `SlickSeekerSupport`:

```scala
import slick.jdbc.PostgresProfile
import io.github.devnico.slickseeker.SlickSeekerSupport
import io.github.devnico.slickseeker.playjson.PlayJsonSeekerSupport

trait MyPostgresProfile extends PostgresProfile
  with SlickSeekerSupport
  with PlayJsonSeekerSupport {

  object MyApi extends API with SeekImplicits with JsonSeekerImplicits

  override val api: MyApi.type = MyApi
}

object MyPostgresProfile extends MyPostgresProfile
```

### Step 2: Import and Use

```scala
// Import your custom profile API
import MyPostgresProfile.api._

// Define your table
class Users(tag: Tag) extends Table[(Int, String, Timestamp)](tag, "users") {
  def id = column[Int]("id", O.PrimaryKey)
  def name = column[String]("name")
  def createdAt = column[Timestamp]("created_at")
  def * = (id, name, createdAt)
}

val users = TableQuery[Users]

// Create a seeker
val seeker = users.toSeeker
  .seek(_.name.asc)
  .seek(_.id.asc)

// Paginate forward
val page1 = db.run(seeker.page(limit = 20, cursor = None))
// PaginatedResult(total=100, items=[...], nextCursor=Some("..."), prevCursor=None)

val page2 = db.run(seeker.page(limit = 20, cursor = page1.nextCursor))

// Paginate backward
val page0 = db.run(seeker.page(limit = 20, cursor = page1.prevCursor))
```

### Supported Profiles

Slick Seeker works with any Slick JDBC profile:

```scala
import io.github.devnico.slickseeker.SlickSeekerSupport
import io.github.devnico.slickseeker.playjson.PlayJsonSeekerSupport

// PostgreSQL
trait MyPostgresProfile extends slick.jdbc.PostgresProfile
  with SlickSeekerSupport
  with PlayJsonSeekerSupport {
  object MyApi extends API with SeekImplicits with JsonSeekerImplicits
  override val api: MyApi.type = MyApi
}
object MyPostgresProfile extends MyPostgresProfile

// MySQL
trait MyMySQLProfile extends slick.jdbc.MySQLProfile
  with SlickSeekerSupport
  with PlayJsonSeekerSupport {
  object MyApi extends API with SeekImplicits with JsonSeekerImplicits
  override val api: MyApi.type = MyApi
}
object MyMySQLProfile extends MyMySQLProfile

// H2
trait MyH2Profile extends slick.jdbc.H2Profile
  with SlickSeekerSupport
  with PlayJsonSeekerSupport {
  object MyApi extends API with SeekImplicits with JsonSeekerImplicits
  override val api: MyApi.type = MyApi
}
object MyH2Profile extends MyH2Profile

// And any other Slick JDBC profile...
```

### Advanced Features

#### Custom Enum Ordering

```scala
sealed trait Priority
object Priority {
  case object Low extends Priority
  case object Medium extends Priority
  case object High extends Priority
  case object Critical extends Priority
}

implicit val priorityOrder: SeekOrder[Priority] =
  SeekOrder(IndexedSeq(Priority.Low, Priority.Medium, Priority.High, Priority.Critical))

val seeker = tasks.toSeeker
  .seek(_.priority.asc) // Uses custom order, not enum ordinal
  .seek(_.id.asc)
```

#### NULL Handling

```scala
val seeker = users.toSeeker
  .seek(_.lastLogin.asc.nullsLast) // NULLs appear at the end
  .seek(_.id.asc)
```
