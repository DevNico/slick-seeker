# Cookbook

Real-world examples and patterns.

## REST API Endpoint

```scala
import io.github.devnico.slickseeker._
import play.api.mvc._
import play.api.libs.json._

// Import your profile API
import MyPostgresProfile.api._

class UserController @Inject()(
  cc: ControllerComponents,
  db: Database
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  implicit val userFormat: Format[User] = Json.format[User]

  def list(
    cursor: Option[String],
    limit: Option[Int],
    sort: Option[String]
  ): Action[AnyContent] = Action.async {
    val seeker = sort match {
      case Some("name") => users.toSeeker.seek(_.name.asc).seek(_.id.asc)
      case Some("email") => users.toSeeker.seek(_.email.asc).seek(_.id.asc)
      case _ => users.toSeeker.seek(_.id.asc)
    }

    // Use pageWithoutCount to skip the COUNT(*) query for better performance
    db.run(seeker.pageWithoutCount(limit.getOrElse(20), cursor, maxLimit = 100))
      .map(result => Ok(Json.toJson(result)))

    // Or use page to include the total count
    db.run(seeker.page(limit.getOrElse(20), cursor, maxLimit = 100))
      .map(result => Ok(Json.toJson(result)))
  }
}
```

## Filtering with Pagination

```scala
def searchUsers(
  nameFilter: Option[String],
  activeOnly: Boolean,
  cursor: Option[String],
  limit: Int
): Future[PaginatedResultWithoutCount[User]] = {

  val baseQuery = users
    .filterOpt(nameFilter)(_.name like _)
    .filterIf(activeOnly)(_.active === true)

  val seeker = baseQuery.toSeeker
    .seek(_.name.asc)
    .seek(_.id.asc)

  // Use pageWithoutCount for filtered queries where the total is less useful
  db.run(seeker.pageWithoutCount(limit, cursor))
}
```

## Joined Tables

```scala
case class Order(id: Int, userId: Int, total: Double, createdAt: Timestamp)
case class User(id: Int, name: String, email: String)
case class OrderWithUser(order: Order, userName: String)

val ordersWithUsers = orders
  .join(users).on(_.userId === _.id)
  .map { case (o, u) => (o, u.name) }

val seeker = ordersWithUsers.toSeeker
  .seek(t => t._1.createdAt.desc)  // Order by order date
  .seek(t => t._1.id.asc)          // Tiebreaker
  .map { case (order, userName) => 
    OrderWithUser(order, userName)
  }
```

## Aggregated Results

```scala
case class UserStats(userId: Int, orderCount: Int, totalSpent: Double)

val userStats = orders
  .groupBy(_.userId)
  .map { case (userId, orders) =>
    (userId, orders.length, orders.map(_.total).sum)
  }

val seeker = userStats.toSeeker
  .seek(_._3.desc)  // Sort by total spent
  .seek(_._1.asc)   // Tiebreaker: user ID
```

## Complex Sorting

### Multi-Level Priority

=== "Scala 3"

    ```scala
    enum Priority {
      case Critical, High, Normal, Low
    }

    enum Status {
      case Open, InProgress, Completed
    }

    given SeekOrder[Priority] = SeekOrder(IndexedSeq(
      Priority.Critical, Priority.High, Priority.Normal, Priority.Low
    ))

    given SeekOrder[Status] = SeekOrder(IndexedSeq(
      Status.Open, Status.InProgress, Status.Completed
    ))

    case class Task(
      id: Int,
      title: String,
      priority: Priority,
      status: Status,
      dueDate: Option[Timestamp]
    )

    val seeker = tasks.toSeeker
      .seek(_.priority.asc)           // Critical first
      .seek(_.status.asc)             // Open first
      .seek(_.dueDate.nullsLast.asc)  // Due date (overdue first)
      .seek(_.id.asc)                 // Tiebreaker
    ```

=== "Scala 2"

    ```scala
    sealed trait Priority
    object Priority {
      case object Critical extends Priority
      case object High extends Priority
      case object Normal extends Priority
      case object Low extends Priority
    }

    sealed trait Status
    object Status {
      case object Open extends Status
      case object InProgress extends Status
      case object Completed extends Status
    }

    implicit val priorityOrder: SeekOrder[Priority] = SeekOrder(IndexedSeq(
      Priority.Critical, Priority.High, Priority.Normal, Priority.Low
    ))

    implicit val statusOrder: SeekOrder[Status] = SeekOrder(IndexedSeq(
      Status.Open, Status.InProgress, Status.Completed
    ))

    case class Task(
      id: Int,
      title: String,
      priority: Priority,
      status: Status,
      dueDate: Option[Timestamp]
    )

    val seeker = tasks.toSeeker
      .seek(_.priority.asc)           // Critical first
      .seek(_.status.asc)             // Open first
      .seek(_.dueDate.nullsLast.asc)  // Due date (overdue first)
      .seek(_.id.asc)                 // Tiebreaker
    ```

## Sorting Simplified

### Basic Sorting

```scala
val seeker = persons.toSeeker
  .seek(_.lastName.asc)
  .seek(_.firstName.asc)
  .seek(_.id.asc)
```

## PostgreSQL Tuple Optimization

For PostgreSQL databases, use `SlickPgTupleSeeker` for type-safe, tuple-optimized pagination. This generates simpler SQL with compile-time safety guarantees.

### Standard Approach (Default)

```scala
val seeker = users.toSeeker
  .seek(_.name.asc)
  .seek(_.id.asc)
```

Generates SQL like:
```sql
WHERE (name > ?) OR (name = ? AND id > ?)
ORDER BY name ASC, id ASC
```

### PostgreSQL Tuple Approach (Type-Safe)

```scala
val seeker = users.toPgTupleSeekerAsc  // Direction enforced at creation
  .seek(_.name)    // No .asc needed - enforced by type
  .seek(_.id)
```

Generates SQL like:
```sql
WHERE (name, id) > (?, ?)
ORDER BY name ASC, id ASC
```

### When to Use

- **Use `toPgTupleSeekerAsc` / `toPgTupleSeekerDesc`** when:
  - Your database is PostgreSQL 8.2+ or H2 in PostgreSQL mode
  - You have multiple seek columns (2 or more)
  - **All seek columns are non-nullable** (compile-time enforced)
  - **All seek columns have the SAME sort direction** (compile-time enforced)
  - Query performance is critical
  - You want maximum type safety

- **Use standard `.toSeeker`** when:
  - You need database portability (H2, MySQL, SQLite)
  - You have only one seek column
  - Any of your seek columns are nullable (`Option[T]`)
  - You have **mixed sort directions** (e.g., `col1.asc, col2.desc`)
  - You're unsure about database compatibility

### Type Safety Guarantees

`SlickPgTupleSeeker` enforces constraints at **compile time**:

```scala
// ✅ CORRECT: All non-nullable, uniform direction
val ascSeeker = users.toPgTupleSeekerAsc
  .seek(_.name)   // String - OK
  .seek(_.age)    // Int - OK
  .seek(_.id)     // Int - OK

// ✅ CORRECT: All DESC
val descSeeker = users.toPgTupleSeekerDesc
  .seek(_.createdAt)  // Timestamp - OK
  .seek(_.id)         // Int - OK

// ❌ COMPILE ERROR: Nullable column
val broken1 = users.toPgTupleSeekerAsc
  .seek(_.name)
  .seek(_.email)  // Option[String] → COMPILE ERROR!
  .seek(_.id)
// Error: No given instance of type slick.ast.BaseTypedType[Option[String]]

// ❌ IMPOSSIBLE: Mixed directions (type system prevents it)
// Once you choose Asc or Desc, ALL columns must be that direction
```

### Example with Multiple Columns

```scala
// All columns ASC
val seeker = orders.toPgTupleSeekerAsc
  .seek(_.status)
  .seek(_.priority)
  .seek(_.createdAt)
  .seek(_.id)
  
// Or all columns DESC
val descSeeker = orders.toPgTupleSeekerDesc
  .seek(_.createdAt)
  .seek(_.priority)
  .seek(_.status)
  .seek(_.id)

val page = db.run(seeker.page(limit = 50, cursor = None))
```

This generates:
```sql
WHERE (status, priority, created_at, id) > (?, ?, ?, ?)
ORDER BY status ASC, priority ASC, created_at ASC, id ASC
```

**Important:** All columns must have the same direction (all ASC or all DESC). For mixed directions, use the standard `SlickSeeker`.

### Performance Benefits

PostgreSQL tuple comparison offers measurable benefits:

**Query Complexity:**
- Standard: `O(n)` comparisons where `n` = number of columns
- Tuple: `O(1)` single tuple comparison

**Example with 4 columns:**

Standard approach:
```sql
WHERE (col1 > ?) OR 
      (col1 = ? AND col2 > ?) OR
      (col1 = ? AND col2 = ? AND col3 > ?) OR
      (col1 = ? AND col2 = ? AND col3 = ? AND col4 > ?)
-- 10 comparisons, 4 parameters repeated
```

Tuple approach:
```sql
WHERE (col1, col2, col3, col4) > (?, ?, ?, ?)
-- 1 comparison, 4 parameters
```

**Benefits:**
- Simpler query plans (easier for PostgreSQL optimizer)
- Better index utilization (composite index scanned as single key)
- Cleaner logs and explain plans
- Reduced parsing overhead

### Complete Working Example

```scala
import slick.jdbc.PostgresProfile
import io.github.devnico.slickseeker._
import io.github.devnico.slickseeker.playjson._

// 1. Setup profile
trait MyPostgresProfile extends PostgresProfile 
  with SlickSeekerSupport 
  with PlayJsonSeekerSupport {
  
  object MyApi extends API with SeekImplicits with JsonSeekerImplicits
  override val api: MyApi.type = MyApi
}

object MyPostgresProfile extends MyPostgresProfile

// 2. Import API
import MyPostgresProfile.api._
import scala.concurrent.ExecutionContext.Implicits.global

// 3. Define schema
case class Product(
  id: Int,
  name: String,
  category: String,
  price: BigDecimal,
  stock: Int
)

class Products(tag: Tag) extends Table[Product](tag, "products") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def name = column[String]("name")
  def category = column[String]("category")
  def price = column[BigDecimal]("price")
  def stock = column[Int]("stock")
  def * = (id, name, category, price, stock).mapTo[Product]
}

val products = TableQuery[Products]

// 4. Create type-safe seeker
val seeker = products.toPgTupleSeekerAsc
  .seek(_.category)  // Group by category
  .seek(_.price)     // Then by price
  .seek(_.id)        // Tiebreaker

// 5. Paginate
val db = Database.forConfig("mydb")

val page1 = db.run(seeker.page(limit = 50, cursor = None))
// PaginatedResult(total=1000, items=[...], nextCursor=Some("..."))

// Or without count for better performance
val fast1 = db.run(seeker.pageWithoutCount(limit = 50, cursor = None))
// PaginatedResultWithoutCount(items=[...], nextCursor=Some("..."))

val page2 = page1.flatMap { p1 =>
  db.run(seeker.page(limit = 50, cursor = p1.nextCursor))
}

// 6. Reverse direction for descending sort
val descSeeker = products.toPgTupleSeekerDesc
  .seek(_.price)      // Most expensive first
  .seek(_.category)   // Then by category
  .seek(_.id)         // Tiebreaker

val expensiveFirst = db.run(descSeeker.page(limit = 10, cursor = None))
```

### Migration Guide

If you're currently using `SlickSeeker` with uniform non-nullable columns on PostgreSQL:

**Before:**
```scala
val seeker = users.toSeeker
  .seek(_.lastName.asc)
  .seek(_.firstName.asc)
  .seek(_.id.asc)
```

**After (Type-Safe):**
```scala
val seeker = users.toPgTupleSeekerAsc
  .seek(_.lastName)
  .seek(_.firstName)
  .seek(_.id)
```

**Migration checklist:**
1. ✅ All columns non-nullable? (no `Option[T]`)
2. ✅ All columns same direction? (all ASC or all DESC)
3. ✅ Database is PostgreSQL 8.2+ or H2 in PostgreSQL mode?
4. ✅ Want compile-time safety?

If all yes → migrate to `toPgTupleSeekerAsc` / `toPgTupleSeekerDesc`

### Common Pitfalls

#### ❌ Trying to Mix Directions

```scala
// This won't compile - direction fixed at seeker level
val seeker = products.toPgTupleSeekerAsc
  .seek(_.name)
  // No way to make price DESC - type system prevents it!
```

**Solution:** Use standard `SlickSeeker` for mixed directions.

#### ❌ Using Nullable Columns

```scala
case class User(id: Int, name: String, email: Option[String])

// This won't compile - email is Option[String]
val seeker = users.toPgTupleSeekerAsc
  .seek(_.name)
  .seek(_.email)  // ❌ Error: No given instance of BaseTypedType[Option[String]]
```

**Solution:** Use standard `SlickSeeker` or filter out nulls beforehand:
```scala
val activeUsers = users.filter(_.email.isDefined)
// Still can't use PgTupleSeeker because email is still Option[String] type

// Better: Use standard SlickSeeker with nulls handling
val seeker = users.toSeeker
  .seek(_.name.asc)
  .seek(_.email.nullsLast.asc)
  .seek(_.id.asc)
```

#### ❌ Database Not PostgreSQL

```scala
// Using MySQL or SQLite?
val seeker = users.toPgTupleSeekerAsc  // ❌ Will fail at runtime!
  .seek(_.name)
  .seek(_.id)
  
// Runtime error: Syntax error in SQL
// MySQL/SQLite don't support tuple comparison
```

**Solution:** Use standard `SlickSeeker` for database portability.

### Best Practices

**1. Use PgTupleSeeker When:**
```scala
// ✅ PostgreSQL, non-nullable columns, uniform direction
val fastSeeker = orders.toPgTupleSeekerDesc
  .seek(_.createdAt)  // Latest first
  .seek(_.id)         // Tiebreaker
```

**2. Use Standard SlickSeeker When:**
```scala
// ✅ Need nullable handling
val nullableSeeker = users.toSeeker
  .seek(_.email.nullsLast.asc)
  .seek(_.id.asc)

// ✅ Need mixed directions
val mixedSeeker = products.toSeeker
  .seek(_.featured.desc)     // Featured first
  .seek(_.price.asc)         // Then cheapest
  .seek(_.id.asc)            // Tiebreaker

// ✅ Need database portability
val portableSeeker = items.toSeeker  // Works on MySQL, SQLite, H2, etc.
  .seek(_.name.asc)
  .seek(_.id.asc)
```

**3. Always Include a Unique Tiebreaker:**
```scala
// ✅ GOOD: id is unique
val seeker = products.toPgTupleSeekerAsc
  .seek(_.category)
  .seek(_.price)
  .seek(_.id)  // Ensures stable pagination

// ❌ BAD: price might have duplicates
val badSeeker = products.toPgTupleSeekerAsc
  .seek(_.category)
  .seek(_.price)  // No unique tiebreaker - unstable pagination!
```

**4. Match Index Structure:**
```sql
-- If you have this index:
CREATE INDEX idx_products_category_price_id ON products(category, price, id);

-- Use this seeker to leverage it:
val seeker = products.toPgTupleSeekerAsc
  .seek(_.category)  -- Matches index order
  .seek(_.price)
  .seek(_.id)
```

### Troubleshooting

**Compile Error: "No given instance of type BaseTypedType[Option[String]]"**

```scala
// You're trying to use a nullable column
val seeker = users.toPgTupleSeekerAsc
  .seek(_.email)  // email is Option[String]
```

**Fix:** Use standard `SlickSeeker` or ensure column is non-nullable in schema.

**Compile Error: "value toPgTupleSeekerAsc is not a member"**

```scala
// You haven't imported the profile API
val seeker = users.toPgTupleSeekerAsc  // ❌
```

**Fix:** Import your profile API:
```scala
import MyPostgresProfile.api._
```

**Runtime Error: "Syntax error near '>'"**

Database doesn't support tuple comparison. Use standard `SlickSeeker`.

## Custom Cursor Environments

Following are only examples and not meant to copy as-is. Adjust for your use case.

### Identity (Testing)

Useful for debugging - no encoding:

```scala
trait MyProfile extends PostgresProfile 
  with SlickSeekerSupport 
  with PlayJsonSeekerSupport {
  
  object MyApi extends API with SeekImplicits with JsonSeekerImplicits {
    override implicit val cursorEnvironment: CursorEnvironment[JsValue] =
      CursorEnvironment(jsonCursorCodec, IdentityDecorator())
  }
  
  override val api: MyApi.type = MyApi
}
// Cursor looks like: >[1,"Alice"]
```

### Compression

```scala
import java.io._
import java.util.zip._

class GzipDecorator(inner: CursorDecorator = IdentityDecorator()) 
  extends CursorDecorator {
  
  override def encode(value: String): String = {
    val innerEncoded = inner.encode(value)
    val bytes = innerEncoded.getBytes(StandardCharsets.UTF_8)
    val out = new ByteArrayOutputStream()
    val gzip = new GZIPOutputStream(out)
    gzip.write(bytes)
    gzip.close()
    out.toByteArray.map("%02x".format(_)).mkString
  }
  
  override def decode(cursor: String): Either[Throwable, String] = {
    Try {
      val bytes = cursor.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
      val in = new GZIPInputStream(new ByteArrayInputStream(bytes))
      val result = new String(in.readAllBytes(), StandardCharsets.UTF_8)
      in.close()
      result
    }.toEither.flatMap(inner.decode)
  }
}

trait MyProfile extends PostgresProfile 
  with SlickSeekerSupport 
  with PlayJsonSeekerSupport {
  
  object MyApi extends API with SeekImplicits with JsonSeekerImplicits {
    override implicit val cursorEnvironment: CursorEnvironment[JsValue] =
      CursorEnvironment(jsonCursorCodec, Base64Decorator(GzipDecorator()))
  }
  
  override val api: MyApi.type = MyApi
}
```

### HMAC Signing (Prevent Tampering)

Recommended for production - prevents users from crafting malicious cursors:

```scala
import javax.crypto._
import javax.crypto.spec._

class HMACDecorator(
  secret: String,
  inner: CursorDecorator = IdentityDecorator()
) extends CursorDecorator {
  
  private def hmacSha256(data: String, key: String): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
    mac.doFinal(data.getBytes(StandardCharsets.UTF_8))
      .map("%02x".format(_))
      .mkString
  }
  
  override def encode(value: String): String = {
    val innerEncoded = inner.encode(value)
    val signature = hmacSha256(innerEncoded, secret)
    s"$signature:$innerEncoded"
  }
  
  override def decode(cursor: String): Either[Throwable, String] = {
    cursor.split(":", 2) match {
      case Array(sig, data) if sig == hmacSha256(data, secret) =>
        inner.decode(data)
      case _ =>
        Left(new IllegalArgumentException("Invalid cursor signature"))
    }
  }
}

trait MyProfile extends PostgresProfile 
  with SlickSeekerSupport 
  with PlayJsonSeekerSupport {
  
  object MyApi extends API with SeekImplicits with JsonSeekerImplicits {
    override implicit val cursorEnvironment: CursorEnvironment[JsValue] =
      CursorEnvironment(jsonCursorCodec, Base64Decorator(HMACDecorator("your-secret-key")))
  }
  
  override val api: MyApi.type = MyApi
}
```

### Encryption

For sensitive data in cursors:

```scala
import javax.crypto._
import javax.crypto.spec._

class AESDecorator(
  key: String,
  inner: CursorDecorator = IdentityDecorator()
) extends CursorDecorator {
  
  private val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
  private val secretKey = new SecretKeySpec(
    key.getBytes(StandardCharsets.UTF_8).take(16), 
    "AES"
  )
  
  override def encode(value: String): String = {
    val innerEncoded = inner.encode(value)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey)
    val encrypted = cipher.doFinal(innerEncoded.getBytes(StandardCharsets.UTF_8))
    val iv = cipher.getIV
    (iv ++ encrypted).map("%02x".format(_)).mkString
  }
  
  override def decode(cursor: String): Either[Throwable, String] = {
    Try {
      val bytes = cursor.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
      val iv = bytes.take(16)
      val encrypted = bytes.drop(16)
      cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(iv))
      val decrypted = cipher.doFinal(encrypted)
      new String(decrypted, StandardCharsets.UTF_8)
    }.toEither.flatMap(inner.decode)
  }
}

trait MyProfile extends PostgresProfile 
  with SlickSeekerSupport 
  with PlayJsonSeekerSupport {
  
  object MyApi extends API with SeekImplicits with JsonSeekerImplicits {
    override implicit val cursorEnvironment: CursorEnvironment[JsValue] =
      CursorEnvironment(jsonCursorCodec, Base64Decorator(AESDecorator("my-secret-key-16")))
  }
  
  override val api: MyApi.type = MyApi
}
```

## Error Handling

### Invalid Cursors

```scala
def safePagenate(
  seeker: SlickSeeker[_, User, _, _, _],
  cursor: Option[String],
  limit: Int
): Future[Either[String, PaginatedResult[User]]] = {
  
  Future {
    db.run(seeker.page(limit, cursor))
  }.map(Right(_))
   .recover {
     case e: IllegalArgumentException => 
       Left(s"Invalid cursor: ${e.getMessage}")
     case e =>
       Left(s"Database error: ${e.getMessage}")
   }
}
```

### Cursor Expiration

```scala
case class ExpiringCursor(
  values: Seq[JsValue],
  expiresAt: Long
)

class ExpiringCursorCodec extends CursorCodec[JsValue] {
  def encode(values: Seq[JsValue]): String = {
    val cursor = ExpiringCursor(
      values,
      System.currentTimeMillis() + 3600000  // 1 hour
    )
    Json.stringify(Json.toJson(cursor))
  }
  
  def decode(cursor: String): Either[String, Seq[JsValue]] = {
    Try(Json.parse(cursor).as[ExpiringCursor]).toEither
      .left.map(e => s"Invalid cursor: ${e.getMessage}")
      .flatMap { c =>
        if (System.currentTimeMillis() > c.expiresAt) {
          Left("Cursor expired")
        } else {
          Right(c.values)
        }
      }
  }
}

trait MyProfile extends PostgresProfile with SlickSeekerSupport {
  implicit val cursorEnv: CursorEnvironment[JsValue] = 
    CursorEnvironment(ExpiringCursorCodec(), Base64Decorator())
}
```

## Testing

### Test Pagination Completeness

```scala
class UserPaginationSpec extends AnyWordSpec {
  "paginate through all users" in {
    val seeker = users.toSeeker
      .seek(_.name.asc)
      .seek(_.id.asc)
    
    def getAllPages(
      cursor: Option[String] = None,
      acc: Seq[User] = Seq.empty
    ): Future[Seq[User]] = {
      db.run(seeker.page(10, cursor)).flatMap { page =>
        val allItems = acc ++ page.items
        page.nextCursor match {
          case Some(next) => getAllPages(Some(next), allItems)
          case None => Future.successful(allItems)
        }
      }
    }
    
    val allPaginated = await(getAllPages())
    val allDirect = await(db.run(users.result))
    
    allPaginated should contain theSameElementsInOrderAs allDirect
  }
}
```

### Test Bidirectional Consistency

```scala
"forward and backward pagination should be consistent" in {
  val seeker = users.toSeeker.seek(_.name.asc).seek(_.id.asc)
  
  // Go forward
  val p1 = await(db.run(seeker.page(5, None)))
  val p2 = await(db.run(seeker.page(5, p1.nextCursor)))
  val p3 = await(db.run(seeker.page(5, p2.nextCursor)))
  
  // Go backward
  val back2 = await(db.run(seeker.page(5, p3.prevCursor)))
  val back1 = await(db.run(seeker.page(5, back2.prevCursor)))
  
  // Should match
  back2.items shouldBe p2.items
  back1.items shouldBe p1.items
}
```
