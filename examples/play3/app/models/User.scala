package models

import play.api.libs.json._
import java.time.Instant

case class User(
    id: Long,
    email: String,
    name: String,
    age: Option[Int],
    createdAt: Instant,
    updatedAt: Instant
)

object User {
  implicit val userFormat: Format[User] = Json.format[User]
}

case class PaginatedResponse[T](
    total: Int,
    items: Seq[T],
    nextCursor: Option[String],
    prevCursor: Option[String]
)

object PaginatedResponse {
  implicit def paginatedResponseFormat[T: Format]: Format[PaginatedResponse[T]] = 
    Json.format[PaginatedResponse[T]]
}
