# Slick Seeker Play3 Example

Minimal example demonstrating cursor-based pagination with Slick Seeker, Play Framework 3, Scala 3, and H2 database.

## Quick Start

```bash
cd examples/play3
sbt run
```

Open `http://localhost:9000/api/users?limit=5`

## API

### List Users (Paginated)
```bash
# First page
curl "http://localhost:9000/api/users?limit=5"

# Next page (use cursor from response)
curl "http://localhost:9000/api/users?limit=5&cursor=<nextCursor>"
```

### Get User
```bash
curl http://localhost:9000/api/users/1
```

## Key Files

- `app/models/MyH2Profile.scala` - Custom H2 profile with SlickSeeker support
- `app/services/UserService.scala` - Pagination logic
- `app/controllers/UserController.scala` - REST API
- `conf/evolutions/default/1.sql` - Database schema with 10 test users

## How It Works

### 1. Custom Profile

```scala
trait MyH2Profile extends H2Profile with SlickSeekerSupport {
  given CursorEnvironment[JsValue] = PlayJsonSupport.cursorEnvironment(Base64Decorator())
}
```

### 2. Create Seeker

```scala
val seeker = users.toSeeker
  .seek(_.name.asc)   // Primary sort
  .seek(_.id.asc)     // Ensures unique ordering

db.run(seeker.page(limit = limit, cursor = cursor, maxLimit = 100))
```

### 3. Response

```json
{
  "total": 10,
  "items": [...],
  "nextCursor": "W1siQm9iIEJhcm5lcyIsMl0sIkZvcndhcmQiXQ==",
  "prevCursor": null
}
```

## Features

- **O(1) Performance** - Cursor-based pagination, no OFFSET
- **Bidirectional** - Navigate forward and backward
- **Type-Safe** - Compile-time cursor verification
- **URL-Safe Cursors** - Base64 encoded for safe use in URLs

## Learn More

- [Slick Seeker Documentation](https://devnico.github.io/slick-seeker)
