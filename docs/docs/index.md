# Slick Seeker

Type-safe, high-performance cursor-based pagination for Slick 3.5+.

## Features

- **Keyset Pagination** - O(1) performance regardless of page depth
- **Bidirectional** - Navigate forward and backward through result sets
- **Type-Safe** - Compile-time verification of cursor/column matching
- **PostgreSQL Tuple Optimization** - Compile-time safe tuple comparisons for PostgreSQL (NEW!)
- **Profile Agnostic** - Works with any Slick JDBC profile (PostgreSQL, MySQL, H2, SQLite, Oracle, etc.)
- **Flexible Ordering** - Support for nulls first/last, custom enum orders
- **Modular** - Core + optional Play JSON integration
- **Composable** - Chain decorators for Base64, compression, encryption

## Why Cursor-Based Pagination?

Traditional offset-based pagination (`OFFSET` + `LIMIT`) has serious performance issues:

```sql
-- Page 1000: Database must scan and skip 19,900 rows!
SELECT * FROM users ORDER BY name LIMIT 100 OFFSET 19900;
```

Problems:

- Slow for deep pages (O(n) where n = offset)
- Unstable with concurrent writes (items shift between pages)
- Memory intensive for large offsets

Cursor-based pagination (keyset pagination) solves this:

```sql
-- Any page: Fast index-based lookup!
SELECT * FROM users
WHERE name > 'last_name' OR (name = 'last_name' AND id > last_id)
ORDER BY name, id 
LIMIT 100;
```

Benefits:

- Constant O(1) performance for any page
- Stable with concurrent writes
- Efficient index usage

## Installation

Add to your `build.sbt`:

```scala
libraryDependencies ++= Seq(
  "io.github.devnico" %% "slick-seeker" % "0.4.0",
  "io.github.devnico" %% "slick-seeker-play-json" % "0.4.0"  // Optional, but you need some kind of cursor encoder
)
```

## Quick Example

```scala
// Step 1: Create a custom profile
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

// Step 2: Import your profile API
import MyPostgresProfile.api._

// Step 3: Define your table
case class User(id: Int, name: String, email: String)

class Users(tag: Tag) extends Table[User](tag, "users") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def name = column[String]("name")
  def email = column[String]("email")
  def * = (id, name, email).mapTo[User]
}

val users = TableQuery[Users]

// Create a seeker
val seeker = users.toSeeker
  .seek(_.name.asc)      // Primary sort
  .seek(_.id.asc)        // Tiebreaker

// Paginate!
val page1 = db.run(seeker.page(limit = 20, cursor = None))
// PaginatedResult(total=100, items=[...], nextCursor=Some("..."), prevCursor=None)

val page2 = db.run(seeker.page(limit = 20, cursor = page1.nextCursor))
// Continue pagination...
```


## Learn More

- [Quick Start](quickstart.md) - Get up and running in 5 minutes
- [Core Concepts](concepts.md) - Understand cursor pagination and decorators
- [Cookbook](cookbook.md) - Real-world examples and patterns

## Requirements

- Scala 2.13.14+, 3.3.4+, 3.5.2+
- Slick 3.6.1+
- Your Slick profile API must be imported before slick-seeker

## License

Apache License 2.0 - Copyright © 2025 Nicolas Schlecker
