package io.github.devnico.slickseeker.support

import org.scalactic.TripleEquals

/** Makes `===` Slick's again in specs: on Scala 3, scalactic's implicit `===` (from Matchers) wins over Slick's in
  * query lambdas, and `filter(_.name === "x")` becomes `where false`.
  */
trait SlickEquals { this: TripleEquals =>
  override def convertToEqualizer[T](left: T): Equalizer[T] = new Equalizer(left)
}
