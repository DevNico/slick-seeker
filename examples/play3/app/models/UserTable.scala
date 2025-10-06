package models

import models.MyH2Profile.api._
import java.time.Instant
import java.sql.Timestamp

class UserTable(tag: Tag) extends Table[User](tag, "users") {
  def id        = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def email     = column[String]("email")
  def name      = column[String]("name")
  def age       = column[Option[Int]]("age")
  def createdAt = column[Instant]("created_at")
  def updatedAt = column[Instant]("updated_at")

  def * = (id, email, name, age, createdAt, updatedAt).mapTo[User]
}

object UserTable {
  val users = TableQuery[UserTable]
}
