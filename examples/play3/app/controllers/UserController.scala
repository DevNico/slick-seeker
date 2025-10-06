package controllers

import models._
import services.UserService
import play.api.libs.json._
import play.api.mvc._
import javax.inject._
import scala.concurrent.ExecutionContext

@Singleton
class UserController @Inject() (
    cc: ControllerComponents,
    userService: UserService
)(implicit ec: ExecutionContext)
    extends AbstractController(cc) {

  def listUsers(limit: Option[Int], cursor: Option[String]) = Action.async { implicit request =>
    val pageLimit = limit.getOrElse(10)
    
    userService.listUsers(pageLimit, cursor).map { result =>
      val response = PaginatedResponse(
        total = result.total,
        items = result.items,
        nextCursor = result.nextCursor,
        prevCursor = result.prevCursor
      )
      Ok(Json.toJson(response))
    }.recover {
      case ex: Exception =>
        BadRequest(Json.obj("error" -> ex.getMessage))
    }
  }

  def getUser(id: Long) = Action.async { implicit request =>
    userService.getUser(id).map {
      case Some(user) => Ok(Json.toJson(user))
      case None       => NotFound(Json.obj("error" -> s"User with id $id not found"))
    }
  }
}
