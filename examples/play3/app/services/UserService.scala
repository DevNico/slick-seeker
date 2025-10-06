package services

import models._
import models.MyH2Profile.api._
import models.MyH2Profile.Implicits._
import models.MyH2Profile.given
import io.github.devnico.slickseeker.pagination.PaginatedResult
import io.github.devnico.slickseeker.playjson.PlayJsonSupport.{jsonCursorValueCodec, jsonOptionCursorValueCodec}
import play.api.db.slick.DatabaseConfigProvider
import slick.jdbc.JdbcProfile
import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UserService @Inject() (
    dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext) {

  private val dbConfig = dbConfigProvider.get[JdbcProfile]
  private val db       = dbConfig.db
  import UserTable.users

  def listUsers(limit: Int = 10, cursor: Option[String] = None): Future[PaginatedResult[User]] = {
    val seeker = users.toSeeker
      .seek(_.name.asc)
      .seek(_.id.asc)

    db.run(seeker.page(limit = limit, cursor = cursor, maxLimit = 100))
  }

  def getUser(id: Long): Future[Option[User]] = {
    db.run(users.filter(_.id === id).result.headOption)
  }
}
