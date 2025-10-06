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
): Future[PaginatedResult[User]] = {
  
  val baseQuery = users
    .filterOpt(nameFilter)(_.name like _)
    .filterIf(activeOnly)(_.active === true)
  
  val seeker = baseQuery.toSeeker
    .seek(_.name.asc)
    .seek(_.id.asc)
  
  db.run(seeker.page(limit, cursor))
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

## Custom Cursor Environments

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
