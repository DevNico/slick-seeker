# Quick Start

Get started with Slick Seeker in 5 minutes.

## Installation

Add to your `build.sbt`:

```scala
libraryDependencies ++= Seq(
  "io.github.devnico" %% "slick-seeker" % "0.1.0",
  "io.github.devnico" %% "slick-seeker-play-json" % "0.1.0"  // Optional
)
```

## Basic Usage

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

**Why?** This pattern allows Slick Seeker to work with any JDBC profile (PostgreSQL, MySQL, H2, SQLite, Oracle, etc.) without being tied to a specific database. By defining the cursor environment inside your profile, it's available wherever you import the profile API.

### Step 2: Import Your Profile API

```scala
// Import your custom profile API
import MyPostgresProfile.api._
```

This provides:
- All Slick query methods (`filter`, `sortBy`, `map`, etc.)
- Slick Seeker's `.toSeeker` extension method
- All necessary type classes and implicits
- Your cursor environment

### Step 3: Define Your Table

```scala
case class User(id: Int, name: String, email: String, active: Boolean)

class Users(tag: Tag) extends Table[User](tag, "users") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def name = column[String]("name")
  def email = column[String]("email")
  def active = column[Boolean]("active")
  
  def * = (id, name, email, active).mapTo[User]
}

val users = TableQuery[Users]
```

### Step 4: Create a Seeker

```scala
val seeker = users.toSeeker
  .seek(_.name.asc)      // Primary sort: name ascending
  .seek(_.id.asc)        // Tiebreaker: id ascending
```

### Step 5: Paginate

```scala
import scala.concurrent.ExecutionContext.Implicits.global

// First page
val page1: Future[PaginatedResult[User]] = 
  db.run(seeker.page(limit = 20, cursor = None))

// Next page (use cursor from previous page)
val page2: Future[PaginatedResult[User]] = 
  page1.flatMap { p1 =>
    db.run(seeker.page(limit = 20, cursor = p1.nextCursor))
  }

// Previous page (bidirectional navigation)
val backToPage1: Future[PaginatedResult[User]] =
  page2.flatMap { p2 =>
    db.run(seeker.page(limit = 20, cursor = p2.prevCursor))
  }
```

## PaginatedResult

The `page()` method returns a `PaginatedResult[T]`:

```scala
case class PaginatedResult[T](
  total: Int,              // Total number of items
  items: Seq[T],           // Current page items
  nextCursor: Option[String],  // Cursor for next page (None if last page)
  prevCursor: Option[String]   // Cursor for previous page (None if first page)
)
```

## Using with Play JSON

For REST APIs, you can serialize `PaginatedResult` to JSON:

```scala
import io.github.devnico.slickseeker.playjson._
import play.api.libs.json.Json

implicit val userFormat = Json.format[User]

val result: PaginatedResult[User] = ???
val json = Json.toJson(result)
// {
//   "total": 100,
//   "items": [...],
//   "nextCursor": "eyJkaXJlY3Rpb24iOiJmb3J3YXJkIiwiLi4u",
//   "prevCursor": null
// }
```

## Common Patterns

### Sort by Multiple Columns

```scala
val seeker = users.toSeeker
  .seek(_.active.desc)    // Active users first
  .seek(_.name.asc)       // Then by name
  .seek(_.id.asc)         // Tiebreaker
```

### Handle Nullable Columns

```scala
case class Person(id: Int, firstName: String, lastName: Option[String])

val seeker = persons.toSeeker
  .seek(_.lastName.nullsLast.asc)  // NULLs at the end
  .seek(_.firstName.asc)
  .seek(_.id.asc)
```

### Reverse Sort Direction

```scala
// Sort descending
val seeker = users.toSeeker
  .seek(_.name.desc)
  .seek(_.id.desc)

// Or use seekDirection
val seeker2 = users.toSeeker
  .seek(_.name)
  .seek(_.id)
  .seekDirection(SortDirection.Desc)
```

## What's Next?

- [Core Concepts](concepts.md) - Deep dive into cursor pagination and decorators
- [Cookbook](cookbook.md) - Real-world examples
