package io.github.devnico.slickseeker.support

/** Sort direction for pagination */
sealed trait SortDirection extends Product with Serializable

object SortDirection {
  case object Asc  extends SortDirection
  case object Desc extends SortDirection
}
