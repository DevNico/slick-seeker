package io.github.devnico.slickseeker.databases

import org.testcontainers.cockroachdb.CockroachContainer
import org.testcontainers.containers.JdbcDatabaseContainer
import org.testcontainers.mariadb.MariaDBContainer
import org.testcontainers.mssqlserver.MSSQLServerContainer
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.oracle.OracleContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import slick.jdbc.JdbcBackend.Database

import java.util.UUID

/** A database the seekers are tested against: in memory or in a container (Testcontainers, so Docker).
  *
  * Database suites only run for the databases selected with the system property `seeker.databases` or the environment
  * variable `SEEKER_DATABASES`: ids separated by commas, or `all`. Without either, the in-memory ones run that need no
  * Docker and work on every Java version (H2, HSQLDB, SQLite). A selected database that can't be started fails its
  * suite instead of skipping it. Suites run one at a time, so each container is stopped before the next starts.
  */
sealed abstract class TestDatabase(val id: String, val name: String, val profile: MatrixProfile) {

  /** The column type to declare instead of Slick's default, by column name (e.g. `nvarchar` on SQL Server). */
  def sqlType(column: String): Option[String] = None

  /** Cases that fail on this database for a documented reason, by name: their suites check that they still do. */
  def limitations: Map[String, String] = Map.empty

  /** A connection to the database (started on first use) with extra JDBC URL `properties` (`;a=b` or `&a=b`). */
  def open(properties: String = ""): Database

  /** Stops the database. */
  def stop(): Unit = ()

  def selected: Boolean = TestDatabase.selected(id)
}

object TestDatabase {
  final class InMemory(id: String, name: String, profile: MatrixProfile, driver: String, url: String => String)
      extends TestDatabase(id, name, profile) {
    private lazy val dbName = s"seek_matrix_${UUID.randomUUID().toString.replace("-", "")}"
    def open(properties: String): Database =
      Database.forURL(url(dbName) + properties, driver = driver, keepAliveConnection = true)
  }

  final class InContainer(
      id: String,
      name: String,
      profile: MatrixProfile,
      driver: String,
      container: => JdbcDatabaseContainer[?],
      sqlTypes: Map[String, String] = Map.empty,
      override val limitations: Map[String, String] = Map.empty
  ) extends TestDatabase(id, name, profile) {
    private var running: Option[JdbcDatabaseContainer[?]] = None
    private def started: JdbcDatabaseContainer[?] = synchronized {
      running.getOrElse {
        val c: JdbcDatabaseContainer[?] = container
        running = Some(c) // stopped even if it fails to start
        c.start()
        c
      }
    }
    override def sqlType(column: String): Option[String] = sqlTypes.get(column)
    def open(properties: String): Database =
      Database.forURL(started.getJdbcUrl + properties, started.getUsername, started.getPassword, driver = driver)
    override def stop(): Unit = synchronized {
      running.foreach(_.stop())
      running = None
    }
  }

  // Images pinned for reproducible runs; bump them deliberately.
  val H2 = new InMemory("h2", "H2", MatrixProfile.H2, "org.h2.Driver", n => s"jdbc:h2:mem:$n;DB_CLOSE_DELAY=-1")
  val Hsqldb =
    new InMemory("hsqldb", "HSQLDB", MatrixProfile.Hsqldb, "org.hsqldb.jdbc.JDBCDriver", n => s"jdbc:hsqldb:mem:$n")
  val SQLite = new InMemory(
    "sqlite",
    "SQLite",
    MatrixProfile.SQLite,
    "org.sqlite.JDBC",
    n => s"jdbc:sqlite:file:$n?mode=memory&cache=shared"
  )
  val Derby = new InMemory(
    "derby",
    "Derby",
    MatrixProfile.Derby,
    "org.apache.derby.iapi.jdbc.AutoloadedDriver",
    n => s"jdbc:derby:memory:$n;create=true"
  )
  val PostgreSql = new InContainer(
    "postgresql",
    "PostgreSQL",
    MatrixProfile.PostgreSql,
    "org.postgresql.Driver",
    new PostgreSQLContainer("postgres:16.15-alpine")
  )
  val MySql =
    new InContainer(
      "mysql",
      "MySQL",
      MatrixProfile.MySql,
      "com.mysql.cj.jdbc.Driver",
      new MySQLContainer("mysql:8.4.13")
    )
  val MariaDb = new InContainer(
    "mariadb",
    "MariaDB",
    MatrixProfile.MySql,
    "org.mariadb.jdbc.Driver",
    new MariaDBContainer("mariadb:11.4.13"),
    limitations = Map(
      "weight (REAL), id" -> "MariaDB Connector/J sends parameters as text: 0.1 never equals a REAL 0.1f, pages repeat"
    )
  )
  val SqlServer = new InContainer(
    "sqlserver",
    "SQL Server",
    MatrixProfile.SqlServer,
    "com.microsoft.sqlserver.jdbc.SQLServerDriver",
    new MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-CU27-ubuntu-22.04").acceptLicense(),
    // Unicode strings need nvarchar; legacy timestamps are datetime (1/300 s), Slick's default is datetime2(6)
    Map("uname" -> "NVARCHAR(40)", "legacy_at" -> "DATETIME")
  )
  val Oracle = new InContainer(
    "oracle",
    "Oracle",
    MatrixProfile.Oracle,
    "oracle.jdbc.OracleDriver",
    new OracleContainer("gvenzl/oracle-free:23.26.3-slim-faststart"),
    // Slick's CHAR(1) for booleans doesn't take the values Oracle 23's driver binds ('true')
    Map("active" -> "NUMBER(1)")
  )
  val CockroachDb = new InContainer(
    "cockroachdb",
    "CockroachDB",
    MatrixProfile.CockroachDb,
    "org.postgresql.Driver",
    new CockroachContainer("cockroachdb/cockroach:v25.4.18")
  )

  val all: Seq[TestDatabase] =
    Seq(H2, Hsqldb, SQLite, Derby, PostgreSql, MySql, MariaDb, SqlServer, Oracle, CockroachDb)

  private val defaults = Set("h2", "hsqldb", "sqlite")

  val selectedIds: Set[String] = {
    val setting = sys.props.get("seeker.databases").orElse(sys.env.get("SEEKER_DATABASES")).map(_.trim)
    val ids     = setting.fold(defaults)(_.split(",").map(_.trim.toLowerCase).filter(_.nonEmpty).toSet)
    if (ids == Set("all")) all.map(_.id).toSet
    else {
      val unknown = ids -- all.map(_.id)
      require(unknown.isEmpty, s"Unknown databases ${unknown.mkString(", ")}; known: ${all.map(_.id).mkString(", ")}")
      ids
    }
  }

  def selected(id: String): Boolean = selectedIds(id)
}
