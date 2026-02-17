package io.github.devnico.slickseeker.pagination

import io.github.devnico.slickseeker.cursor.CursorEnvironment
import slick.jdbc.JdbcProfile

import scala.concurrent.ExecutionContext

/** Common base trait for all seeker implementations.
  *
  * Provides a unified interface for cursor-based pagination across different seeker variants.
  *
  * @tparam U
  *   The unpacked type of query results
  * @tparam CVE
  *   The cursor value encoding type
  */
trait Seeker[U, CVE] {

  /** Execute a paginated query with cursor-based navigation.
    *
    * @param limit
    *   Maximum number of items to return
    * @param cursor
    *   Optional cursor for pagination navigation
    * @param maxLimit
    *   Maximum allowed limit
    * @param profile
    *   JDBC profile for database operations
    * @param cursorEnvironment
    *   Environment for encoding/decoding cursors
    * @param ec
    *   Execution context for async operations
    * @return
    *   Database action that returns a paginated result
    */
  def page[Profile <: JdbcProfile](
      limit: Int,
      cursor: Option[String],
      maxLimit: Int
  )(implicit
      profile: Profile,
      cursorEnvironment: CursorEnvironment[CVE],
      ec: ExecutionContext
  ): profile.api.DBIOAction[PaginatedResult[U], profile.api.NoStream, profile.api.Effect.Read]

  /** Execute a paginated query without computing the total count.
    *
    * This avoids the extra COUNT(*) query.
    *
    * @param limit
    *   Maximum number of items to return
    * @param cursor
    *   Optional cursor for pagination navigation
    * @param maxLimit
    *   Maximum allowed limit
    * @param profile
    *   JDBC profile for database operations
    * @param cursorEnvironment
    *   Environment for encoding/decoding cursors
    * @param ec
    *   Execution context for async operations
    * @return
    *   Database action that returns a paginated result without total count
    */
  def pageWithoutCount[Profile <: JdbcProfile](
      limit: Int,
      cursor: Option[String],
      maxLimit: Int
  )(implicit
      profile: Profile,
      cursorEnvironment: CursorEnvironment[CVE],
      ec: ExecutionContext
  ): profile.api.DBIOAction[PaginatedResultWithoutCount[U], profile.api.NoStream, profile.api.Effect.Read]
}
