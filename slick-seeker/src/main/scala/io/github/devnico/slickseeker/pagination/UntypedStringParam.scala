package io.github.devnico.slickseeker.pagination

import slick.ast.{FieldSymbol, OptionTypedType, ScalaType, TypedType}
import slick.jdbc.{JdbcProfile, JdbcType, JdbcTypesComponent}

import java.sql.{PreparedStatement, ResultSet, Types}
import scala.reflect.ClassTag

/** A string-backed cursor parameter bound untyped (`Types.OTHER`), like the ad-hoc seeker's literals: PostgreSQL then
  * infers its type from the column, so it compares with enums, `uuid` and other types mapped to `String`, which a
  * `varchar` parameter does not (`operator does not exist: my_enum > character varying`).
  */
final private[slickseeker] class UntypedStringParam(underlying: JdbcType[Any], asString: Any => String)
    extends JdbcType[Any] {
  def sqlType: Int                                           = underlying.sqlType
  def sqlTypeName(size: Option[FieldSymbol]): String         = underlying.sqlTypeName(size)
  def setValue(v: Any, p: PreparedStatement, idx: Int): Unit = p.setObject(idx, asString(v), Types.OTHER)
  def setNull(p: PreparedStatement, idx: Int): Unit          = p.setNull(idx, Types.OTHER)
  def getValue(r: ResultSet, idx: Int): Any                  = underlying.getValue(r, idx)
  def wasNull(r: ResultSet, idx: Int): Boolean               = underlying.wasNull(r, idx)
  def updateValue(v: Any, r: ResultSet, idx: Int): Unit      = underlying.updateValue(v, r, idx)
  def valueToSQLLiteral(value: Any): String                  = underlying.valueToSQLLiteral(value)
  def hasLiteralForm: Boolean                                = underlying.hasLiteralForm
  def scalaType: ScalaType[Any]                              = underlying.scalaType
  override def toString: String                              = s"UntypedStringParam($underlying)"
  override def classTag: ClassTag[?]                         = underlying.classTag
}

private[slickseeker] object UntypedStringParam {
  private val stringSqlTypes =
    Set(Types.VARCHAR, Types.CHAR, Types.LONGVARCHAR, Types.NVARCHAR, Types.NCHAR, Types.LONGNVARCHAR)

  private def isString(jdbcType: JdbcType[?]): Boolean =
    stringSqlTypes(jdbcType.sqlType) && jdbcType.scalaType.classTag.runtimeClass == classOf[String]

  /** `tpe` with string-backed values (`String` or a `MappedColumnType` to `String`) bound untyped; others unchanged. */
  def apply(profile: JdbcProfile)(tpe: TypedType[Any]): TypedType[Any] = (tpe: TypedType[?]) match {
    case option: OptionTypedType[?] =>
      apply(profile)(option.elementType.asInstanceOf[TypedType[Any]]).optionType.asInstanceOf[TypedType[Any]]
    case _ =>
      profile.jdbcTypeFor(tpe) match {
        case mapped: JdbcTypesComponent#MappedJdbcType[Any @unchecked, Any @unchecked] if isString(mapped.tmd) =>
          new UntypedStringParam(mapped, v => mapped.map(v).asInstanceOf[String])
        case jdbcType if isString(jdbcType) =>
          new UntypedStringParam(jdbcType, _.asInstanceOf[String])
        case _ => tpe
      }
  }
}
