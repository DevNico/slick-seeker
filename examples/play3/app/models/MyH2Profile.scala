package models

import slick.jdbc.H2Profile
import io.github.devnico.slickseeker.SlickSeekerSupport
import io.github.devnico.slickseeker.cursor._
import io.github.devnico.slickseeker.playjson._
import play.api.libs.json.JsValue

/** Custom H2 profile with SlickSeeker support.
  *
  * This profile extends H2Profile and adds cursor-based pagination capabilities.
  * The cursor environment uses Play JSON for encoding/decoding and Base64 for decoration.
  */
trait MyH2Profile extends H2Profile with SlickSeekerSupport {
  given CursorEnvironment[JsValue] = PlayJsonSupport.cursorEnvironment(Base64Decorator())
}

object MyH2Profile extends MyH2Profile
